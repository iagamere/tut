package com.abdo.ps4monitor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Everything the "PS4" screen shows. All of it is read through PS4 server (the /v2/fs routes and /v2/sysinfo), so it also works while a game is running.
 * NOTHING here is verified on a real console: the folder names below are the usual PS4 ones from public documentation, and the screen shows
 * a "Check paths" report so you can see exactly which of them the console answered.
 */
data class Capture(val path: String, val name: String, val size: Long, val mtime: Long, val video: Boolean, val titleId: String)
data class GameInfo(val titleId: String, val title: String, val version: String, val category: String, val metaDir: String?, val sfoPath: String?, val sfo: Map<String, String> = emptyMap())
data class Volume(val label: String, val path: String, val total: Long, val free: Long)
data class PathCheck(val path: String, val ok: Boolean, val detail: String)
class CaptureScan(val items: List<Capture>, val notes: List<String>, val truncated: Boolean)

object Ps4Info {
    private val PHOTO = setOf("jpg", "jpeg", "png", "bmp", "webp")
    private val VIDEO = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val TITLE_ID = Regex("""^(CUSA|PPSA|NPXS|NPXX)\d{4,6}$""")
    /** title id -> title, filled when the games list was read (used to name captures). */
    @Volatile var names: Map<String, String> = emptyMap()
    const val CAPTURE_ROOT = "/user/av_contents"

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private fun child(dir: String, n: String) = if (dir.endsWith("/")) dir + n else "$dir/$n"

    /** Lists a folder through the server, mapping every failure to a short English sentence (Tx translates the common ones). */
    private fun listOrNote(ps4: Ps4, path: String, notes: MutableList<String>): List<FsEntry>? = try { Ps4Server.fsList(ps4, path, 8000) }
        catch (e: Ps4Server.FsUnavailable) { notes.add("$path: not allowed or not reachable (${e.message})"); null }
        catch (e: Ps4Error) { notes.add("$path: ${e.message}"); null }
        catch (e: Exception) { notes.add("$path: ${e.message}"); null }

    /** Photos and videos under /user/av_contents (and the user's extra folders), newest first. At most [limit] files and [maxDirs] folders are visited. */
    /** Shared worker threads for the scans below: dozens of small requests to the PS4 are sent six at a time instead of one after the other. */
    private val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(6) { r -> Thread(r).apply { isDaemon = true } }
    private val capCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CaptureScan>>()
    private val gamesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<List<GameInfo>, List<String>>>>()
    /** Forget the remembered scans (after deleting a capture or a game). */
    fun invalidate() { capCache.clear(); gamesCache.clear() }

    fun scanCaptures(ps4: Ps4, limit: Int = 2000, maxDirs: Int = 400, force: Boolean = false): CaptureScan {
        capCache[ps4.id]?.let { (t, sc) -> if (!force && System.currentTimeMillis() - t < 30_000L) return sc }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>()); val out = ArrayList<Capture>()
        var dirs = 0; var truncated = false
        // only these two folders: anything else under av_contents (previews, thumbnails...) is ignored on purpose
        val roots = listOf("$CAPTURE_ROOT/photo", "$CAPTURE_ROOT/video")
        var level: List<Triple<String, Int, String>> = roots.map { Triple(it, 0, "") }
        while (level.isNotEmpty()) {
            if (dirs >= maxDirs || out.size >= limit) { truncated = true; break }
            val take = level.take(maxDirs - dirs)
            if (take.size < level.size) truncated = true
            dirs += take.size
            // one level at a time, the folders of a level are listed in parallel
            val results = pool.invokeAll(take.map { t -> java.util.concurrent.Callable { t to listOrNote(ps4, t.first, notes) } })
                .map { runCatching { it.get() }.getOrNull() }
            val next = ArrayList<Triple<String, Int, String>>()
            for (r in results) {
                if (r == null) continue
                val (t, l) = r
                if (l == null) continue
                val (dir, depth, tid) = t
                for (e in l) {
                    val p = child(dir, e.name)
                    if (e.isDir) { if (depth < 5) next.add(Triple(p, depth + 1, if (TITLE_ID.matches(e.name)) e.name else tid)) }
                    else {
                        val x = ext(e.name)
                        val video = x in VIDEO
                        if (x in PHOTO || video) { if (out.size < limit) out.add(Capture(p, e.name, e.size, e.mtime, video, tid)) else truncated = true }
                    }
                }
            }
            level = next
        }
        val sc = CaptureScan(out.distinctBy { it.path }.sortedByDescending { it.mtime }, synchronized(notes) { ArrayList(notes) }, truncated)
        capCache[ps4.id] = System.currentTimeMillis() to sc
        return sc
    }

    // ---------------- games ----------------
    /** Minimal PARAM.SFO reader (key table + data table). Returns {} when the bytes are not an SFO. */
    fun parseSfo(b: ByteArray): Map<String, String> {
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (b.size < 20 || b[0].toInt() != 0 || b[1].toInt() != 'P'.code || b[2].toInt() != 'S'.code || b[3].toInt() != 'F'.code) return emptyMap()
        val keyOff = u32(8); val dataOff = u32(12); val n = u32(16)
        if (n < 0 || n > 512 || keyOff < 0 || dataOff < 0) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (i in 0 until n) {
            val e = 20 + i * 16
            if (e + 16 > b.size) break
            val kOff = keyOff + u16(e); val fmt = u16(e + 2); val len = u32(e + 4); val dOff = dataOff + u32(e + 12)
            if (kOff < 0 || kOff >= b.size || dOff < 0 || len < 0 || dOff.toLong() + len > b.size) continue
            var k = kOff; while (k < b.size && b[k].toInt() != 0) k++
            val key = String(b, kOff, k - kOff, Charsets.US_ASCII)
            if (fmt == 0x0404) m[key] = u32(dOff).toString()
            else { var l = 0; while (l < len && b[dOff + l].toInt() != 0) l++; m[key] = String(b, dOff, l, Charsets.UTF_8) }
        }
        return m
    }

    internal val META_DIRS = listOf("/user/appmeta", "/system_data/priv/appmeta")
    private val GAME_DIRS = listOf("/user/app", "/user/appmeta")

    /** Installed titles: folder names of /user/app (or /user/appmeta), enriched from each title's param.sfo when the console lets us read it. */
    fun scanGames(ps4: Ps4, limit: Int = 300, force: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<GameInfo>, List<String>> {
        gamesCache[ps4.id]?.let { (t, r) -> if (!force && System.currentTimeMillis() - t < 60_000L) { progress(r.first.size, r.first.size); names = r.first.associate { it.titleId to it.title }; return r } }
        val notes = ArrayList<String>(); var ids: List<String> = emptyList()
        for (d in GAME_DIRS) {
            val l = listOrNote(ps4, d, notes) ?: continue
            ids = l.filter { it.isDir && TITLE_ID.matches(it.name) }.map { it.name }
            if (ids.isNotEmpty()) break
        }
        if (ids.isEmpty()) return emptyList<GameInfo>() to notes
        val picked = ids.sorted().take(limit)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        progress(0, picked.size)
        // each title's param.sfo is read on its own worker thread (six at a time)
        val tasks = picked.map { id ->
            java.util.concurrent.Callable {
                var sfo: Map<String, String> = emptyMap(); var sfoPath: String? = null; var meta: String? = null
                for (m in META_DIRS) {
                    val p = "$m/$id/param.sfo"
                    val bytes = try { Ps4Server.fsRead(ps4, p, 0, 64 * 1024, 6000) } catch (e: Exception) { ByteArray(0) }
                    if (bytes.isNotEmpty()) { val s = runCatching { parseSfo(bytes) }.getOrDefault(emptyMap()); if (s.isNotEmpty()) { sfo = s; sfoPath = p; meta = m; break } }
                }
                progress(done.incrementAndGet(), picked.size)
                GameInfo(id, sfo["TITLE"] ?: id, sfo["APP_VER"] ?: sfo["VERSION"] ?: "", sfo["CATEGORY"] ?: "", meta, sfoPath, sfo)
            }
        }
        val res = ArrayList<GameInfo>(pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() })
        progress(res.size, res.size)
        names = res.associate { it.titleId to it.title }
        val out = res to notes
        gamesCache[ps4.id] = System.currentTimeMillis() to out
        return out
    }
    fun categoryLabel(c: String) = when (c) {
        "gd" -> tr("Game", "لعبة"); "gp" -> tr("Update", "تحديث"); "ac" -> tr("Add-on", "محتوى إضافي"); "gde" -> tr("Game", "لعبة")
        "" -> ""; else -> c
    }

    /** Candidate paths of a title's artwork files (icon0.png = square cover, pic0/pic1.png = 16:9 backgrounds). The folder that held param.sfo comes first. */
    fun art(g: GameInfo, vararg files: String): List<String> {
        val dirs = (listOfNotNull(g.metaDir) + META_DIRS).distinct()
        return files.flatMap { f -> dirs.map { "$it/${g.titleId}/$f" } }
    }
    /** SYSTEM_VER of param.sfo is a BCD number: 0x07550000 = firmware 7.55. */
    fun firmware(v: String?): String? {
        val n = v?.toLongOrNull() ?: return null
        if (n <= 0) return null
        val h = String.format("%08x", n)
        return (h.substring(0, 2).trimStart('0').ifEmpty { "0" }) + "." + h.substring(2, 4)
    }

    // ---------------- storage ----------------
    /** Volumes the server can measure. Several folders can sit on one volume, so identical (total, free) pairs are shown once. */
    fun storage(ps4: Ps4): Pair<List<Volume>, List<String>> {
        val notes = ArrayList<String>(); val seen = HashSet<Pair<Long, Long>>(); val out = ArrayList<Volume>()
        val cands = ArrayList<Pair<String, String>>()
        cands.add(tr("Data (/data)", "البيانات (/data)") to "/data")
        cands.add(tr("User partition", "قسم المستخدم") to CAPTURE_ROOT)
        for (i in 0..7) cands.add(tr("USB $i", "USB $i") to "/mnt/usb$i")
        cands.add(tr("Extended storage", "التخزين الموسّع") to "/mnt/ext0")
        for ((label, path) in cands) {
            try {
                val (t, f) = Ps4Server.fsSpace(ps4, path, 5000)
                if (t > 0 && seen.add(t to f)) out.add(Volume(label, path, t, f))
            } catch (e: Ps4Server.FsUnavailable) { if (!path.startsWith("/mnt/usb") && !path.startsWith("/mnt/ext")) notes.add("$path: not allowed or not reachable (${e.message})") }
            catch (e: Ps4Error) { if (!path.startsWith("/mnt/usb") && !path.startsWith("/mnt/ext")) notes.add("$path: ${e.message}") }
            catch (e: Exception) { notes.add("$path: ${e.message}") }
        }
        return out to notes
    }

    // ---------------- diagnostics ----------------
    fun checkPaths(ps4: Ps4): List<PathCheck> {
        val paths = (Ps4Server.RO_DEFAULT + Ps4Server.roExtra() + Ps4Server.fsRootsKnown(ps4) + listOf(CAPTURE_ROOT + "/photo", CAPTURE_ROOT + "/video")).distinct()
        return paths.map { p ->
            try { val l = Ps4Server.fsList(ps4, p, 6000); PathCheck(p, true, "${l.count { it.isDir }} folders, ${l.count { !it.isDir }} files") }
            catch (e: Ps4Server.FsUnavailable) { PathCheck(p, false, "not allowed or not reachable (${e.message})") }
            catch (e: Ps4Error) { PathCheck(p, false, e.message ?: "error") }
            catch (e: Exception) { PathCheck(p, false, e.message ?: "error") }
        }
    }

    /** Device facts: label -> value, from the console's own sysctl. Unknown names are simply missing; nothing is guessed. */
    fun deviceRows(j: JSONObject?): Pair<List<Pair<String, String>>, List<String>> {
        if (j == null) return emptyList<Pair<String, String>>() to emptyList()
        val v = j.optJSONObject("values") ?: JSONObject()
        val rows = ArrayList<Pair<String, String>>()
        fun s(k: String) = if (v.has(k)) v.optString(k) else null
        s("kern.ostype")?.let { rows.add(tr("System", "النظام") to it) }
        s("kern.osrelease")?.let { rows.add(tr("System release", "إصدار النظام") to it) }
        s("kern.version")?.let { rows.add(tr("Kernel version", "إصدار النواة") to it.lines().first().take(120)) }
        s("hw.model")?.let { rows.add(tr("CPU model", "المعالج") to it) }
        s("hw.machine")?.let { rows.add(tr("Architecture", "المعمارية") to it) }
        if (v.has("hw.ncpu")) rows.add(tr("CPU cores", "أنوية المعالج") to v.optLong("hw.ncpu").toString())
        if (v.has("hw.physmem")) rows.add(tr("Memory seen by the system", "الذاكرة التي يراها النظام") to Fmt.bytes(v.optLong("hw.physmem")))
        if (v.has("uptime_seconds")) rows.add(tr("Up for", "يعمل منذ") to Fmt.dur(v.optLong("uptime_seconds")))
        val miss = j.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return rows to miss
    }
}

/**
 * Decoded images, shared by the grids and the viewer. At most 3 reads at a time so the PS4's server is not flooded.
 * Everything runs on Dispatchers.IO: these functions are called from composables (main thread), and a network call there would throw
 * NetworkOnMainThreadException, which used to be swallowed as "no image". Small sizes (<= 512 px) are also kept on disk.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(3)
    private val videoGate = Semaphore(1)
    const val MAX_READ = 64L shl 20
    /** Why the last image failed (shown next to a failed picture). */
    @Volatile var lastError: String = ""
    private var dir: File? = null
    private var writes = 0

    fun init(c: Context) { dir = File(c.cacheDir, "thumbs").also { it.mkdirs() } }
    fun key(ps4: Ps4, path: String, maxSide: Int) = "${ps4.id}|$path|$maxSide"
    fun cached(key: String): Bitmap? = cache.get(key)
    fun peek(ps4: Ps4, path: String, maxSide: Int): Bitmap? = cache.get(key(ps4, path, maxSide))
    fun peekVideo(ps4: Ps4, path: String, maxSide: Int): Bitmap? = cache.get(key(ps4, "$path#v", maxSide))
    fun peekAny(ps4: Ps4, paths: List<String>, maxSide: Int): Bitmap? = paths.firstNotNullOfOrNull { peek(ps4, it, maxSide) }

    // ---- disk cache (small thumbnails only)
    private fun file(k: String): File? = dir?.let { d ->
        File(d, MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { b -> "%02x".format(b) } + ".jpg")
    }
    private fun diskGet(k: String): Bitmap? = file(k)?.takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
    private fun diskPut(k: String, b: Bitmap) {
        val f = file(k) ?: return
        try { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } } catch (e: Exception) { f.delete(); return }
        if (++writes % 100 == 0) {
            val fs = dir?.listFiles() ?: return
            if (fs.size > 4000) fs.sortedBy { it.lastModified() }.take(fs.size - 3000).forEach { it.delete() }
        }
    }

    fun decode(b: ByteArray, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: OutOfMemoryError) { null }

    private fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height); if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** Null when the file is too big, not an image, or could not be read ([lastError] says why). Safe to call from the main thread. */
    suspend fun load(ps4: Ps4, path: String, size: Long, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(ps4, path, maxSide)
        cache.get(k)?.let { return@withContext it }
        val dk = "${ps4.id}|$path|$size|$maxSide"
        if (maxSide <= 512) diskGet(dk)?.let { cache.put(k, it); return@withContext it }
        gate.withPermit {
            cache.get(k) ?: try {
                val sz = if (size > 0) size else Ps4Server.fsSize(ps4, path, 6000)
                if (sz <= 0) { lastError = tr("File not found", "الملف غير موجود"); null }
                else if (sz > MAX_READ) { lastError = tr("File too large", "الملف كبير جدًا"); null }
                else {
                    val bmp = decode(Ps4Server.fsRead(ps4, path, 0, sz.toInt(), 30_000), maxSide)
                    if (bmp == null) { lastError = tr("Not a readable image", "ليست صورة قابلة للقراءة"); null }
                    else { cache.put(k, bmp); if (maxSide <= 512) diskPut(dk, bmp); bmp }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; null }
        }
    }

    /** The first of [paths] that exists and decodes (artwork can live in more than one folder). */
    suspend fun loadAny(ps4: Ps4, paths: List<String>, maxSide: Int): Bitmap? {
        for (p in paths) { val b = load(ps4, p, 0, maxSide); if (b != null) return b }
        return null
    }

    /** A frame of a video, read with partial range requests (only a few MB of the file are fetched). Best effort: null when the codec/container is not supported. */
    suspend fun videoFrame(ps4: Ps4, path: String, size: Long, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(ps4, "$path#v", maxSide)
        cache.get(k)?.let { return@withContext it }
        val dk = "${ps4.id}|$path#v|$size|$maxSide"
        diskGet(dk)?.let { cache.put(k, it); return@withContext it }
        videoGate.withPermit {
            cache.get(k) ?: try {
                val sz = if (size > 0) size else Ps4Server.fsSize(ps4, path, 6000)
                if (sz <= 0) null else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(RangeSource(ps4, path, sz))
                        val f = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L)
                        f?.let { fit(it, maxSide) }?.also { cache.put(k, it); diskPut(dk, it) }
                    } finally { runCatching { r.release() } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
    }

    /** MediaDataSource over /v2/fs/read: 1 MiB blocks, 6 kept, and at most 64 MB fetched per video so a thumbnail can never download a whole clip. */
    private class RangeSource(val ps4: Ps4, val path: String, val total: Long) : MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = this.size > 6
        }
        private var fetched = 0L
        override fun getSize(): Long = total
        override fun close() { blocks.clear() }
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= total) return -1
            val want = minOf(size.toLong(), total - position).toInt()
            var done = 0
            while (done < want) {
                val pos = position + done; val idx = pos / BLOCK
                val blk: ByteArray = blocks[idx] ?: run {
                    if (fetched > BUDGET) throw IOException("thumbnail budget used")
                    val start = idx * BLOCK; val len = minOf(BLOCK.toLong(), total - start).toInt()
                    val d = Ps4Server.fsRead(ps4, path, start, len, 20_000)
                    if (d.isEmpty()) throw IOException("empty read")
                    fetched += d.size; blocks[idx] = d; d
                }
                val inBlk = (pos - idx * BLOCK).toInt()
                if (inBlk >= blk.size) break
                val n = minOf(want - done, blk.size - inBlk)
                System.arraycopy(blk, inBlk, buffer, offset + done, n); done += n
            }
            return if (done == 0) -1 else done
        }
        companion object { const val BLOCK = 1 shl 20; const val BUDGET = 64L shl 20 }
    }
}
