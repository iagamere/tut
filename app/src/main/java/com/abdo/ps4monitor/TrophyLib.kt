package com.abdo.ps4monitor
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A console account: its folder id (8 hex digits under /user/home) and the name from username.dat. */
data class Account(val id: String, val name: String, val avatarPaths: List<String>)
/** One trophy of a game. [grade] is P (platinum), G, S, B, or ? when the trophy file does not say. */
data class Trophy(val id: Int, val name: String, val detail: String, val grade: Char, val hidden: Boolean, val icon: String?)
/** Trophy list read from the game's own trophy pack (trp file); [files] maps entry name -> (offset, size) for the pictures. */
data class TrophyPack(val npwr: String, val title: String, val trophies: List<Trophy>, val trpPath: String, val files: Map<String, Pair<Long, Long>>, val namesReadable: Boolean)
/** [unlocked]: trophy id -> time (seconds, 0 when unknown); null = the console's unlock data could not be read. */
class TrophyState(val unlocked: Map<Int, Long>?, val notes: List<String>, val db: AccountDb? = null)
/** One game's summary row of the console's trophy database (tbl_trophy_title). [counts]: grade -> (earned, total). */
class DbTitle(val dbId: Int, val npwr: String, val title: String, val progress: Int, val total: Int, val unlockedNum: Int, val counts: Map<Char, Pair<Int, Int>>)
/** One trophy row of tbl_trophy_flag (names and descriptions are stored in the database itself). */
class DbTrophy(val id: Int, val group: Int, val name: String, val detail: String, val grade: Char, val unlocked: Boolean, val visible: Boolean, val time: Long, val icon: String = "")
class AccountDb(val title: DbTitle, val trophies: List<DbTrophy>)

/**
 * Trophy reader. What is VERIFIED from public documentation and what is a GUESS:
 *  - the game's trophy pack (the .trp files in sce_sys/trophy, big-endian table of files) and npbind.dat holding the NPWR id: documented layout.
 *  - the trophy names are plain XML (TROP.SFM / TROPCONF.SFM) only in some packs; if they are encrypted the list shows numbered trophies with their pictures.
 *  - WHERE the console keeps each account's unlocked trophies is NOT verified: the reader searches the account's trophy folder for SQLite files and
 *    matches tables by column names. If nothing matches the screen says so and lists what it found (use Device > Scan trophies and send the result).
 */
object TrophyLib {
    private val NPWR = Regex("NPWR\\d{5}_\\d{2}")
    private val HEX8 = Regex("^[0-9a-fA-F]{8}$")
    private val roSent = ConcurrentHashMap.newKeySet<String>()

    /** The account folders and the profile-picture folders must be readable by the server (sent once per PS4 per app run). */
    private fun ensureRo(ps4: Ps4) { if (roSent.add(ps4.id)) { val ok = runCatching { Ps4Server.setRoExtra(ps4, Ps4Server.roExtra().joinToString("\n")) == null }.getOrDefault(false); if (!ok) roSent.remove(ps4.id) } }

    fun accounts(ps4: Ps4): Pair<List<Account>, List<String>> {
        ensureRo(ps4)
        val notes = ArrayList<String>(); val out = ArrayList<Account>()
        val l = try { Ps4Server.fsList(ps4, "/user/home", 8000) } catch (e: Exception) { notes += "/user/home: ${e.message}"; return out to notes }
        val profRoot = "/system_data/priv/cache/profile"
        val profDirs = try { Ps4Server.fsList(ps4, profRoot, 8000).also { notes += "$profRoot: " + it.take(20).joinToString { x -> x.name } } } catch (e: Exception) { notes += "$profRoot: ${e.message}"; emptyList() }
        val IMG = Regex("(?i).+\\.(png|jpg|jpeg|webp)$")
        fun images(dir: String): List<String> = try {
            Ps4Server.fsList(ps4, dir, 6000).filter { !it.isDir && IMG.matches(it.name) }
                .sortedWith(compareByDescending<FsEntry> { it.name.contains("avatar", true) }.thenByDescending { it.size }).map { "$dir/${it.name}" }
        } catch (e: Exception) { emptyList() }
        for (d in l.filter { it.isDir && HEX8.matches(it.name) }) {
            val id = d.name.lowercase()
            val name = runCatching { String(Ps4Server.fsRead(ps4, "/user/home/${d.name}/username.dat", 0, 64, 5000), Charsets.UTF_8).trim { c -> c == '\u0000' || c.isWhitespace() } }.getOrDefault("").ifBlank { id }
            val av = listOf("avatar.png", "avatar440.png", "avatar260.png", "avatar128.png", "avatar64.png").flatMap { f ->
                listOf(id, "0x$id", d.name).distinct().map { "/system_data/priv/cache/profile/$it/$f" } }
            val dec = id.toLongOrNull(16)?.toString()
            val found = ArrayList<String>()
            profDirs.filter { it.isDir && (it.name.lowercase().removePrefix("0x") == id || it.name == dec) }.forEach { found += images("$profRoot/${it.name}") }
            found += images("/user/home/${d.name}")
            if (found.isEmpty()) notes += "No picture found for $name (looked in $profRoot and /user/home/${d.name})"
            out += Account(d.name, name, (found + av).distinct())
        }
        if (out.isEmpty()) notes += "No account folders found under /user/home."
        return out to notes
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun be64(b: ByteArray, o: Int) = (be32(b, o) shl 32) or be32(b, o + 4)

    /** The game's NPWR id(s) from npbind.dat (searched as text: the file is a small binary table of ids). */
    /** Folders where a game's own files may be: the app-meta folders hold the extracted sce_sys files (param.sfo, npbind.dat, trophy/), so they come first. */
    fun baseDirs(titleId: String): List<String> = Ps4Info.META_DIRS.map { "$it/$titleId" } + listOf("/user/app/$titleId/sce_sys", "/user/app/$titleId")

    fun npwrIds(ps4: Ps4, titleId: String, notes: MutableList<String> = ArrayList()): List<String> {
        for (base in baseDirs(titleId)) {
            val p = "$base/npbind.dat"
            val b = try { Ps4Server.fsRead(ps4, p, 0, 256 * 1024, 6000) } catch (e: Exception) { notes += "$p: ${e.message}"; continue }
            val ids = NPWR.findAll(String(b, Charsets.ISO_8859_1)).map { it.value }.distinct().toList()
            if (ids.isNotEmpty()) return ids
            notes += "$p: read (${b.size} bytes) but no NPWR id inside"
        }
        // last resort: the console's shared trophy folder is named by NPWR id (only usable when exactly one is found for this game, which cannot be known here): not guessed
        return emptyList()
    }

    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").trim()

    fun loadPack(ps4: Ps4, titleId: String, npwrs: List<String>, notes: MutableList<String> = ArrayList()): TrophyPack? {
        val dirs = baseDirs(titleId).map { "$it/trophy" } + npwrs.flatMap { listOf("/user/trophy/conf/$it", "/user/trophy/conf/${it.substringBefore('_')}") }
        for (dir in dirs) loadPackFrom(ps4, dir, npwrs, notes)?.let { return it }
        // Downloaded (digital) games keep no trophy folder next to their files: the pack lives in the console's shared trophy folders,
        // in a folder whose name contains the NPWR id (the suffix and the exact parent folder differ between firmware versions), so look for it by id.
        for (dir in findNpwrDirs(ps4, npwrs, notes)) loadPackFrom(ps4, dir, npwrs, notes)?.let { return it }
        return null
    }

    private val TROPHY_ROOTS = listOf("/user/trophy/conf", "/user/trophy/data", "/user/trophy", "/system_data/priv/trophy")

    /** Folders under the console's trophy roots (up to 3 levels) whose name contains one of the games' NPWR numbers; also a TRP file named after the id. */
    private fun findNpwrDirs(ps4: Ps4, npwrs: List<String>, notes: MutableList<String>): List<String> {
        val keys = npwrs.map { it.substringBefore('_').uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        var budget = 14                                  // at most this many folder listings (each one is a request to the PS4)
        // breadth first: the roots first, deeper only while nothing was found
        var level: List<Pair<String, Int>> = TROPHY_ROOTS.map { it to 0 }
        while (level.isNotEmpty() && found.isEmpty() && budget > 0) {
            val next = ArrayList<Pair<String, Int>>()
            for ((path, depth) in level) {
                if (budget <= 0) break
                budget--
                val l = try { Ps4Server.fsList(ps4, path, 8000) } catch (e: Exception) { continue }
                for (e in l) {
                    val p = "$path/${e.name}"
                    val hit = keys.any { e.name.uppercase().contains(it) }
                    if (e.isDir) { if (hit) found += p else if (depth < 2 && out_ok(e.name)) next += p to depth + 1 }
                    else if (hit && e.name.endsWith(".trp", true)) found += path
                }
            }
            level = next
        }
        notes += if (found.isEmpty()) "No folder with ${keys.joinToString()} under ${TROPHY_ROOTS.joinToString()}: the game's trophy pack is not registered on this console (it is created when the game runs for the first time)."
                 else "Trophy folders found by id: " + found.joinToString()
        return found.toList()
    }
    /** Do not descend into account folders or the pictures folders of other games. */
    private fun out_ok(name: String) = !HEX8.matches(name) && !name.equals("screenshot", true)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** No NPWR id known: look through the console's shared trophy folder and take the pack whose title is this game's title. */
    fun findPackByTitle(ps4: Ps4, gameTitle: String, notes: MutableList<String>): TrophyPack? {
        val root = "/user/trophy/conf"
        val dirs = try { Ps4Server.fsList(ps4, root, 8000).also { notes += "$root: ${it.size} entries" } } catch (e: Exception) { notes += "$root: ${e.message}"; return null }
        val want = norm(gameTitle)
        notes += "$root: " + dirs.take(12).joinToString { it.name } + if (dirs.size > 12) " ..." else ""
        for (d in dirs.filter { it.isDir }.take(200)) {
            val p = loadPackFrom(ps4, "$root/${d.name}", emptyList(), ArrayList()) ?: continue
            val t = norm(p.title)
            if (t.isNotEmpty() && want.isNotEmpty() && (t == want || t.contains(want) || want.contains(t))) return p
        }
        notes += "No pack in $root has the title \"$gameTitle\"."
        return null
    }

    /** Lists what is really in the game's folders, for the diagnostic text. */
    fun listing(ps4: Ps4, titleId: String): List<String> {
        val out = ArrayList<String>()
        for (b in baseDirs(titleId).take(3)) out += try { "$b: " + Ps4Server.fsList(ps4, b, 6000).take(30).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name) } } catch (e: Exception) { "$b: ${e.message}" }
        return out
    }

    private fun hex(b: ByteArray, n: Int = 32) = b.take(n).joinToString(" ") { "%02X".format(it) }

    /** Trophy key published on the PS4 developer wiki (Trophy00.trp page). */
    private val TROPHY_KEY = intArrayOf(0x21, 0xF4, 0x1A, 0x6B, 0xAD, 0x8A, 0x1D, 0x3E, 0xCA, 0x7A, 0xD5, 0x86, 0xC1, 0x01, 0xB7, 0xA9).map { it.toByte() }.toByteArray()

    private fun aesCbc(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /**
     * TROP.ESFM (names and descriptions of a PS4 trophy pack): per the PS4 developer wiki, the AES key of the pack is the NP communication id
     * (12 ASCII bytes, zero-padded to 16) encrypted with the trophy key (AES-128-CBC, zero IV); the file is a 16-byte IV followed by the
     * AES-128-CBC encrypted XML. Returns the XML when the result contains <trophyconf, otherwise null. The wiki leaves the key-generation IV
     * and the padding open, so two readings are tried; neither was checked against a real file here.
     */
    internal fun decryptEsfm(npwr: String, data: ByteArray): String? {
        if (npwr.length < 12 || data.size < 48) return null
        return try {
            val id = ByteArray(16); val ascii = npwr.toByteArray(Charsets.US_ASCII)
            System.arraycopy(ascii, 0, id, 0, minOf(16, ascii.size))
            val key = aesCbc(true, TROPHY_KEY, ByteArray(16), id)
            val attempts = listOf(16 to data.copyOfRange(0, 16), 0 to ByteArray(16))
            for ((skip, iv) in attempts) {
                val n = (data.size - skip) / 16 * 16
                if (n <= 0) continue
                val xml = String(aesCbc(false, key, iv, data.copyOfRange(skip, skip + n)), Charsets.UTF_8).trimEnd('\u0000')
                if (xml.contains("<trophyconf", true)) return xml
            }
            null
        } catch (e: Exception) { null }
    }

    private fun loadPackFrom(ps4: Ps4, dir: String, npwrs: List<String>, notes: MutableList<String>): TrophyPack? {
        val all = try { Ps4Server.fsList(ps4, dir, 8000) } catch (e: Exception) { notes += "$dir: ${e.message}"; return null }
        val trps = all.filter { !it.isDir && it.name.endsWith(".trp", true) }.sortedBy { it.name }
        if (trps.isEmpty()) notes += "$dir: no .trp file (found: " + all.take(12).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name + " " + it.size + " B") } + ")"
        else notes += "$dir: " + all.take(12).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name + " " + it.size + " B") }
        for (t in trps) {
            val path = "$dir/${t.name}"
            val head = try { Ps4Server.fsRead(ps4, path, 0, 64, 8000) } catch (e: Exception) { notes += "$path: cannot read (${e.message})"; continue }
            if (head.size < 64) { notes += "$path: only ${head.size} bytes read"; continue }
            if (be32(head, 0) != 0xDCA24D00L) { notes += "$path: unknown header: " + hex(head); continue }
            val count = be32(head, 0x10).toInt(); val elSize = be32(head, 0x14).toInt().let { if (it in 0x20..0x100) it else 0x40 }
            if (count !in 1..4000) { notes += "$path: odd file count $count (header " + hex(head, 24) + ")"; continue }
            val table = try { Ps4Server.fsRead(ps4, path, 0x40, count * elSize, 15000) } catch (e: Exception) { notes += "$path: table unreadable (${e.message})"; continue }
            if (table.size < count * elSize) { notes += "$path: table cut (${table.size} of ${count * elSize} B)"; continue }
            val files = LinkedHashMap<String, Pair<Long, Long>>()
            for (i in 0 until count) {
                val o = i * elSize
                val nm = String(table, o, 32, Charsets.ISO_8859_1).substringBefore('\u0000')
                files[nm] = be64(table, o + 32) to be64(table, o + 40)
            }
            val PNG = Regex("^TROP\\d{3}\\.PNG$", RegexOption.IGNORE_CASE)
            if (files.keys.none { PNG.matches(it) }) {
                // the table is not where the PS3 layout puts it (PS4 packs differ): show the raw bytes and look for the picture names directly
                notes += "$path: table not in the expected place (count $count, entry size $elSize). header: " + hex(head, 48) + " | at 0x40: " + hex(table, 32)
                val scan = try { Ps4Server.fsRead(ps4, path, 0, 0x20000, 15000) } catch (e: Exception) { ByteArray(0) }
                val txt = String(scan, Charsets.ISO_8859_1)
                val hits = Regex("TROP\\d{3}\\.PNG", RegexOption.IGNORE_CASE).findAll(txt).map { it.range.first }.toList()
                if (hits.size >= 2) {
                    val stride = hits[1] - hits[0]
                    var start = hits[0]
                    // step back over earlier entries whose name field still looks like a file name
                    while (start - stride >= 0 && scan[start - stride].toInt() in 0x20..0x7E && scan[start - stride + 1].toInt() in 0x20..0x7E) start -= stride
                    val fb = LinkedHashMap<String, Pair<Long, Long>>()
                    var o = start
                    while (o + 48 <= scan.size && fb.size < 4000) {
                        val nm = String(scan, o, 32, Charsets.ISO_8859_1).substringBefore('\u0000')
                        if (nm.isEmpty() || nm.any { it.code !in 0x20..0x7E }) break
                        fb[nm] = be64(scan, o + 32) to be64(scan, o + 40)
                        o += stride
                    }
                    notes += "$path: found the file table at 0x${start.toString(16)} with entry size $stride (${fb.size} entries: " + fb.keys.take(6).joinToString() + ")"
                    if (fb.keys.any { PNG.matches(it) }) { files.clear(); files.putAll(fb) }
                } else notes += "$path: no TROPnnn.PNG names in the first ${scan.size} bytes" + (if (scan.isNotEmpty()) " (start: " + hex(scan, 64) + ")" else "")
            }
            val cfg = files.entries.firstOrNull { it.key.endsWith(".SFM", true) }
            var xml = cfg?.let { (_, v) -> runCatching { String(Ps4Server.fsRead(ps4, path, v.first, v.second.coerceAtMost(2L shl 20).toInt(), 15000), Charsets.UTF_8) }.getOrNull() }
            if (xml == null || !xml.contains("<trophyconf", true)) {
                // PS4 packs keep the names in TROP.ESFM (AES-encrypted XML), see decryptEsfm
                val es = files.entries.firstOrNull { it.key.equals("TROP.ESFM", true) } ?: files.entries.firstOrNull { it.key.endsWith(".ESFM", true) }
                if (es != null) {
                    val id = Regex("NPWR\\d{5}_\\d{2}").find(t.name)?.value ?: Regex("NPWR\\d{5}_\\d{2}").find(dir)?.value ?: npwrs.getOrNull(trps.indexOf(t)) ?: npwrs.firstOrNull() ?: ""
                    val raw = runCatching { Ps4Server.fsRead(ps4, path, es.value.first, es.value.second.coerceIn(0L, 2L shl 20).toInt(), 15000) }.getOrNull()
                    val dec = if (raw != null) decryptEsfm(id, raw) else null
                    if (dec != null) xml = dec else notes += "$path: ${es.key} could not be decrypted (id '$id', ${raw?.size ?: -1} bytes, start " + (if (raw != null) hex(raw, 16) else "-") + ")"
                }
            }
            val list = ArrayList<Trophy>(); var title = ""; var readable = false
            if (xml != null && xml.contains("<trophyconf", true)) {
                title = Regex("<title-name>(.*?)</title-name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unesc) ?: ""
                for (m in Regex("<trophy\\s+([^>]*)>(.*?)</trophy>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val a = m.groupValues[1]; val body = m.groupValues[2]
                    fun attr(k: String) = Regex("\\b$k\\s*=\\s*\"([^\"]*)\"").find(a)?.groupValues?.get(1)
                    val id = attr("id")?.toIntOrNull() ?: continue
                    val grade = attr("ttype")?.firstOrNull()?.uppercaseChar() ?: '?'
                    val name = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unesc) ?: ""
                    val detail = Regex("<detail>(.*?)</detail>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unesc) ?: ""
                    list += Trophy(id, name.ifBlank { "Trophy %03d".format(id) }, detail, grade, attr("hidden").equals("yes", true), files.keys.firstOrNull { it.equals("TROP%03d.PNG".format(id), true) })
                }
                readable = list.isNotEmpty()
            }
            if (list.isEmpty()) {            // names are encrypted / absent: number the trophies from their pictures
                files.keys.mapNotNull { n -> Regex("^TROP(\\d{3})\\.PNG$", RegexOption.IGNORE_CASE).find(n)?.let { n to it.groupValues[1].toInt() } }
                    .sortedBy { it.second }.forEach { (n, id) -> list += Trophy(id, "Trophy %03d".format(id), "", '?', false, n) }
            }
            if (list.isEmpty()) { notes += "$path: pack has no trophy pictures or names (entries: " + files.keys.take(8).joinToString() + ")"; continue }
            val npwr = Regex("NPWR\\d{5}_\\d{2}").find(t.name)?.value ?: Regex("NPWR\\d{5}_\\d{2}").find(dir)?.value ?: npwrs.getOrNull(trps.indexOf(t)) ?: npwrs.firstOrNull() ?: ""
            return TrophyPack(npwr, title, list.sortedBy { it.id }, path, files, readable)
        }
        return null
    }

    private val sig = ConcurrentHashMap<String, String>()
    private val genericNotes = ConcurrentHashMap<String, List<String>>()
    private fun localCopy(ps4: Ps4, path: String, e: FsEntry, cache: File): File? {
        val f = File(cache, "trop_" + (ps4.id + path).hashCode().toUInt().toString(16) + ".db")
        val s = "${e.size}|${e.mtime}"
        if (f.exists() && sig[f.name] == s) return f
        return try { f.outputStream().use { Ps4Server.fsCopyTo(ps4, path, e.size, it) { } }; sig[f.name] = s; f } catch (ex: Exception) { f.delete(); null }
    }

    private fun walkDbs(ps4: Ps4, base: String, depth: Int, out: MutableList<Pair<String, FsEntry>>, notes: MutableList<String>) {
        val l = try { Ps4Server.fsList(ps4, base, 8000) } catch (e: Exception) { if (depth == 0) notes += "$base: ${e.message}"; return }
        for (e in l) {
            val p = "$base/${e.name}"
            if (e.isDir) { if (depth < 4 && out.size < 80) walkDbs(ps4, p, depth + 1, out, notes) }
            else if (e.name.endsWith(".db", true) && e.size in 1..(64L shl 20)) out += p to e
        }
    }

    private fun toSeconds(v: Long): Long = when { v > 100_000_000_000_000L -> v / 1_000_000; v > 100_000_000_000L -> v / 1000; else -> v }

    private fun Cursor.int(n: String): Int { val k = getColumnIndex(n); return if (k < 0 || isNull(k)) 0 else try { getInt(k) } catch (e: Exception) { 0 } }
    private fun Cursor.str(n: String): String { val k = getColumnIndex(n); return if (k < 0 || isNull(k)) "" else try { getString(k) ?: "" } catch (e: Exception) { "" } }
    private fun Cursor.lng(n: String): Long { val k = getColumnIndex(n); return if (k < 0 || isNull(k)) 0L else try { getLong(k) } catch (e: Exception) { 0L } }
    private fun gradeOf(v: Int): Char = when (v) { 1 -> 'P'; 2 -> 'G'; 3 -> 'S'; 4 -> 'B'; else -> '?' }
    /** tbl_trophy_flag.time_unlocked is text like 2024-05-01T12:30:00.00Z (an unlocked-never placeholder is an old date); a number is also accepted. */
    private fun parseTime(s: String): Long {
        if (s.isBlank()) return 0L
        s.toLongOrNull()?.let { return toSeconds(it) }
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
            val t = (f.parse(s.take(19).replace(' ', 'T'))?.time ?: 0L) / 1000
            if (t < 86400L * 366) 0L else t
        } catch (e: Exception) { 0L }
    }

    private fun copyTo(ps4: Ps4, path: String, e: FsEntry, dest: File): Boolean =
        try { dest.outputStream().use { Ps4Server.fsCopyTo(ps4, path, e.size, it) { } }; true } catch (ex: Exception) { dest.delete(); false }

    /**
     * The console keeps every account's trophies in /user/home/<id>/trophy/db/trophy_local.db (SQLite). Layout taken from the open-source tool
     * Apollo (bucanero/apollo-ps4, saves.c / sqlite_db.c), which reads and edits that same file:
     *   tbl_trophy_title(id, trophy_title_id = NPWR id, title, trophy_num, unlocked_trophy_num, progress, platinum_num, unlocked_platinum_num, gold_..., silver_..., bronze_..., status)
     *   tbl_trophy_flag(title_id = tbl_trophy_title.id, trophyid, groupid, title, description, grade 1..4 = P/G/S/B, unlocked, visible, time_unlocked)
     * Not verified here on a console: that the read-only folders let the server hand over this file, and that the file is not mid-write.
     */
    private fun hasTable(db: SQLiteDatabase, n: String) = db.rawQuery("select count(*) from sqlite_master where type='table' and name=?", arrayOf(n)).use { c -> c.moveToFirst() && c.getInt(0) > 0 }

    /**
     * trophy_net.db layout (seen in a real console's schema dump, not from documentation):
     *   titles(id, npcommid = NPWR id, trophy_num, platinum_num..bronze_num, title, ...), trophies(id, title_id, group_id, trophyid, grade, hidden, title, description, rare, earned_rate, small_icon_url, icon_url),
     *   user_title_map(user_id, title_id, progress, unlocked_trophy_num, unlocked_*_num, ...), user_trophy_map(user_id, trophy_id, time_unlocked).
     * It only holds games whose trophies were synced with PSN. The meaning of the grade numbers is not documented, so it is derived from the title's own per-grade counts.
     * Returns null when this database has no such tables, or has no entry for the game ([notes] says which).
     */
    private fun readNet(db: SQLiteDatabase, account: Account, npwr: String, gameTitle: String, notes: MutableList<String>, opened: BooleanArray): AccountDb? {
        if (!hasTable(db, "titles") || !hasTable(db, "trophies") || !hasTable(db, "user_title_map") || !hasTable(db, "user_trophy_map")) return null
        opened[0] = true
        class T(val id: Int, val npwr: String, val title: String, val total: Int, val pn: Int, val gn: Int, val sn: Int, val bn: Int)
        val titles = ArrayList<T>()
        db.rawQuery("select * from titles", null).use { c ->
            while (c.moveToNext()) titles += T(c.int("id"), c.str("npcommid"), c.str("title"), c.int("trophy_num"), c.int("platinum_num"), c.int("gold_num"), c.int("silver_num"), c.int("bronze_num"))
        }
        if (titles.isEmpty()) { notes += "${account.name}: trophy_net.db has no games (nothing synced yet)"; return null }
        val key = npwr.substringBefore('_'); val want = norm(gameTitle)
        val pick = titles.firstOrNull { npwr.isNotEmpty() && it.npwr.equals(npwr, true) }
            ?: titles.firstOrNull { key.isNotEmpty() && it.npwr.startsWith(key, true) }
            ?: titles.firstOrNull { want.isNotEmpty() && norm(it.title) == want }
            ?: titles.filter { want.isNotEmpty() && norm(it.title).let { x -> x.isNotEmpty() && (x.contains(want) || want.contains(x)) } }.minByOrNull { Math.abs(norm(it.title).length - want.length) }
        if (pick == null) { notes += "${account.name}: this game is not in trophy_net.db (${titles.size} synced games: " + titles.take(6).joinToString { it.title } + ")"; return null }
        class Row(val id: Int, val group: Int, val tid: Int, val grade: Int, val hidden: Boolean, val name: String, val detail: String, val icon: String)
        val rows = ArrayList<Row>()
        db.rawQuery("select * from trophies where title_id = ? order by trophyid", arrayOf(pick.id.toString())).use { c ->
            while (c.moveToNext()) rows += Row(c.int("id"), c.int("group_id"), c.int("trophyid"), c.int("grade"), c.int("hidden") != 0, c.str("title"), c.str("description"), c.str("icon_url").ifBlank { c.str("small_icon_url") })
        }
        if (rows.isEmpty()) { notes += "${account.name}: ${pick.title} is in trophy_net.db but has no trophy rows"; return null }
        // which console user: the one with the most progress on this game (a database can list more than one user)
        data class Um(val user: Long, val progress: Int, val unlocked: Int)
        val ums = ArrayList<Um>()
        db.rawQuery("select * from user_title_map where title_id = ?", arrayOf(pick.id.toString())).use { c ->
            while (c.moveToNext()) ums += Um(c.lng("user_id"), c.int("progress"), c.int("unlocked_trophy_num"))
        }
        val um = ums.maxByOrNull { it.unlocked }
        val times = HashMap<Int, Long>()      // trophy row id -> unlock time (seconds)
        if (um != null) db.rawQuery("select * from user_trophy_map where user_id = ?", arrayOf(um.user.toString())).use { c -> while (c.moveToNext()) { val raw = c.str("time_unlocked"); if (raw.isNotBlank()) times[c.int("trophy_id")] = parseTime(raw) } }
        // grade numbers -> P/G/S/B by comparing with the title's own counts; the first candidate mapping that reproduces them exactly wins
        val dist = rows.groupingBy { it.grade }.eachCount()
        val want4 = mapOf('P' to pick.pn, 'G' to pick.gn, 'S' to pick.sn, 'B' to pick.bn)
        val maps = listOf(mapOf(1 to 'P', 2 to 'G', 3 to 'S', 4 to 'B'), mapOf(0 to 'P', 1 to 'G', 2 to 'S', 3 to 'B'), mapOf(4 to 'P', 3 to 'G', 2 to 'S', 1 to 'B'), mapOf(3 to 'P', 2 to 'G', 1 to 'S', 0 to 'B'))
        val gm = maps.firstOrNull { m -> want4.values.sum() > 0 && want4.all { (ch, n) -> m.entries.filter { it.value == ch }.sumOf { dist[it.key] ?: 0 } == n } && dist.keys.all { it in m } }
        if (gm == null) notes += "grade numbers ${dist} do not match the title's counts (P${pick.pn} G${pick.gn} S${pick.sn} B${pick.bn}): grades are not shown"
        val list = rows.map { r -> DbTrophy(r.tid, r.group, r.name, r.detail, gm?.get(r.grade) ?: '?', times.containsKey(r.id), !r.hidden, times[r.id] ?: 0L, r.icon) }
        val earned = list.count { it.unlocked }
        val pr = um?.progress?.takeIf { it in 0..100 } ?: if (pick.total > 0) earned * 100 / pick.total else 0
        val counts = LinkedHashMap<Char, Pair<Int, Int>>()
        for ((ch, tot) in want4) if (tot > 0) counts[ch] = list.count { it.grade == ch && it.unlocked } to tot
        notes += "${account.name}: read ${pick.title} from trophy_net.db (PSN-synced copy; users in table: ${ums.size}, trophies ${list.size}, earned $earned)"
        return AccountDb(DbTitle(pick.id, pick.npwr, pick.title, pr, if (pick.total > 0) pick.total else list.size, earned, counts), list)
    }

    /** Tables, columns and row counts of a database file, for the technical notes (so an unknown layout can be seen instead of guessed). */
    private fun schemaOf(db: SQLiteDatabase, name: String): String {
        val sb = StringBuilder("$name: ")
        try {
            val tables = ArrayList<String>()
            db.rawQuery("select name from sqlite_master where type='table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
            if (tables.isEmpty()) sb.append("no tables")
            for (t in tables.take(30)) {
                val n = db.rawQuery("select count(*) from \"$t\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1 }
                val cols = db.rawQuery("select * from \"$t\" limit 0", null).use { it.columnNames.joinToString(",") }
                sb.append("[$t $n rows: $cols] ")
            }
        } catch (e: Exception) { sb.append("cannot read schema: ${e.message}") }
        return sb.toString()
    }

    /** [opened] is set once a database with the trophy tables was read, so "this account has no record of the game" can be told from "the file could not be read". */
    fun accountDb(ps4: Ps4, account: Account, npwr: String, gameTitle: String, cache: File, notes: MutableList<String>, opened: BooleanArray = BooleanArray(1)): AccountDb? {
        val tdir = "/user/home/${account.id}/trophy"
        val dir = "$tdir/db"
        notes += "$tdir: " + (try { Ps4Server.fsList(ps4, tdir, 8000).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name) } } catch (e: Exception) { e.message ?: "?" })
        val l = try { Ps4Server.fsList(ps4, dir, 8000) } catch (e: Exception) { notes += "$dir: ${e.message}"; return null }
        notes += "$dir: " + l.take(12).joinToString { "${it.name} (${it.size} B)" }
        // trophy_local.db is where Apollo finds the trophies; any other .db in the folder is checked for the same tables and described when it does not match
        val files = l.filter { !it.isDir && it.name.endsWith(".db", true) }.sortedByDescending { it.name == "trophy_local.db" }
        if (files.isEmpty()) { notes += "$dir has no .db file (found: " + l.take(12).joinToString { it.name } + ")"; return null }
        for (main in files) {
            val base = File(cache, "troph_" + (ps4.id + account.id + main.name).hashCode().toUInt().toString(16) + ".db")
            val wal = l.firstOrNull { it.name == main.name + "-wal" && !it.isDir }
            val s = "${main.size}|${main.mtime}|${wal?.size}|${wal?.mtime}"
            if (!(base.exists() && sig[base.name] == s)) {
                File(base.path + "-wal").delete(); File(base.path + "-shm").delete(); File(base.path + "-journal").delete()
                if (!copyTo(ps4, "$dir/${main.name}", main, base)) { notes += "$dir/${main.name}: could not be copied"; continue }
                val walOk = wal == null || wal.size <= 0 || copyTo(ps4, "$dir/${wal.name}", wal, File(base.path + "-wal"))
                if (!walOk) notes += "the -wal file of ${main.name} could not be copied (recent trophies may be missing)" else sig[base.name] = s
            }
            try {
                // this is only a cache copy: read-write is the fallback because a read-only connection to a WAL database can fail on older Android
                val db = try { SQLiteDatabase.openDatabase(base.path, null, SQLiteDatabase.OPEN_READONLY) } catch (e: Exception) { SQLiteDatabase.openDatabase(base.path, null, SQLiteDatabase.OPEN_READWRITE) }
                try {
                    val has = db.rawQuery("select count(*) from sqlite_master where type='table' and name='tbl_trophy_title'", null).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
                    if (!has) {
                        // the console also keeps a PSN-synced copy (trophy_net.db: titles / trophies / user_title_map / user_trophy_map); the layout was read from a real console's notes
                        val net = readNet(db, account, npwr, gameTitle, notes, opened)
                        if (net != null) return net
                        if (!opened[0]) notes += schemaOf(db, main.name) + " (${main.size} B)"
                        continue
                    }
                    opened[0] = true
                    val titles = ArrayList<Pair<DbTitle, Int>>()
                    db.rawQuery("select * from tbl_trophy_title", null).use { c ->
                        while (c.moveToNext()) {
                            val counts = LinkedHashMap<Char, Pair<Int, Int>>()
                            for ((ch, nm) in listOf('P' to "platinum", 'G' to "gold", 'S' to "silver", 'B' to "bronze")) { val tot = c.int("${nm}_num"); if (tot > 0) counts[ch] = c.int("unlocked_${nm}_num") to tot }
                            titles += DbTitle(c.int("id"), c.str("trophy_title_id"), c.str("title"), c.int("progress"), c.int("trophy_num"), c.int("unlocked_trophy_num"), counts) to c.int("status")
                        }
                    }
                    if (titles.isEmpty()) { notes += "tbl_trophy_title is empty for ${account.name} (${main.name})"; continue }
                    val live = titles.filter { it.second == 0 }.ifEmpty { titles }.map { it.first }
                    val key = npwr.substringBefore('_')
                    val want = norm(gameTitle)
                    val pick = live.firstOrNull { npwr.isNotEmpty() && it.npwr.equals(npwr, true) }
                        ?: live.firstOrNull { key.isNotEmpty() && it.npwr.startsWith(key, true) }
                        ?: live.firstOrNull { want.isNotEmpty() && norm(it.title) == want }
                        ?: live.filter { want.isNotEmpty() && norm(it.title).let { x -> x.isNotEmpty() && (x.contains(want) || want.contains(x)) } }
                            .minByOrNull { Math.abs(norm(it.title).length - want.length) }
                    if (pick == null) { notes += "${account.name}: this game is not in ${main.name} (${live.size} games there; wanted ${npwr.ifBlank { gameTitle }})"; continue }
                    val list = ArrayList<DbTrophy>()
                    db.rawQuery("select * from tbl_trophy_flag where title_id = ? order by trophyid", arrayOf(pick.dbId.toString())).use { c ->
                        while (c.moveToNext()) list += DbTrophy(c.int("trophyid"), c.int("groupid"), c.str("title"), c.str("description"), gradeOf(c.int("grade")),
                            c.int("unlocked") != 0, c.int("visible") != 0, parseTime(c.str("time_unlocked")))
                    }
                    return AccountDb(pick, list.filter { it.id >= 0 })
                } finally { db.close() }
            } catch (ex: Exception) { notes += "${main.name} (${account.name}): ${ex.message}" }
        }
        return null
    }


    /**
     * Games that are not synced with PSN have no row in trophy_net.db. The console then keeps the account's progress for that game in
     * /user/home/<id>/trophy/data/<...>/<NPWR>/TROPUSR.DAT (the PS3-style trophy user file: big-endian, magic 81 8F 54 AD, a table of tables;
     * table 6 holds one entry per trophy with its unlocked flag and time). Layout taken from the PS3 format (RPCS3 documents it); NOT verified
     * on a PS4, so every step writes what it found into [notes]. Returns trophy id -> unlock time (seconds), or null when nothing usable was found.
     */
    private fun readUsr(ps4: Ps4, account: Account, npwr: String, notes: MutableList<String>): Map<Int, Long>? {
        val key = npwr.substringBefore('_').uppercase()
        if (key.isEmpty()) return null
        val root = "/user/home/${account.id}/trophy/data"
        val dirs = ArrayList<String>()
        fun walk(path: String, depth: Int) {
            val l = try { Ps4Server.fsList(ps4, path, 8000) } catch (e: Exception) { if (depth == 0) notes += "$path: ${e.message}"; return }
            if (depth == 0) notes += "$path: " + l.take(12).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name + " " + it.size + " B") }
            for (e in l) if (e.isDir) { val p = "$path/${e.name}"; if (e.name.uppercase().contains(key)) dirs += p else if (depth < 3) walk(p, depth + 1) }
        }
        walk(root, 0)
        if (dirs.isEmpty()) { notes += "${account.name}: no $key folder under $root (no local progress file for this game)"; return null }
        for (d in dirs) {
            val l = try { Ps4Server.fsList(ps4, d, 8000) } catch (e: Exception) { notes += "$d: ${e.message}"; continue }
            notes += "$d: " + l.take(12).joinToString { (if (it.isDir) "[" + it.name + "]" else it.name + " " + it.size + " B") }
            val f = l.firstOrNull { !it.isDir && it.name.equals("TROPUSR.DAT", true) } ?: continue
            val path = "$d/${f.name}"
            val b = try { Ps4Server.fsRead(ps4, path, 0, f.size.coerceIn(1L, 4L shl 20).toInt(), 15000) } catch (e: Exception) { notes += "$path: ${e.message}"; continue }
            if (b.size < 0x30 || be32(b, 0) != 0x818F54ADL) { notes += "$path: unknown header: " + hex(b, 32); continue }
            val tables = be32(b, 8).toInt()
            if (tables !in 1..16 || 0x30 + tables * 0x20 > b.size) { notes += "$path: odd table count $tables (header " + hex(b, 48) + ")"; continue }
            val out = HashMap<Int, Long>(); val sum = StringBuilder()
            for (t in 0 until tables) {
                val o = 0x30 + t * 0x20
                val type = be32(b, o).toInt(); val esz = be32(b, o + 4).toInt(); val cnt = be32(b, o + 8).toInt(); val off = be64(b, o + 16)
                sum.append("[type $type: $cnt x $esz] ")
                if (type != 6 || esz !in 0x20..0x400 || cnt !in 1..4000) continue
                for (i in 0 until cnt) {
                    val eo = off + i.toLong() * (esz + 0x10)
                    if (eo < 0 || eo + 0x30 > b.size) break
                    val e = eo.toInt()
                    val id = be32(b, e + 8).toInt(); val state = be32(b, e + 0x14)
                    if (state == 0L) continue
                    val ticks = be64(b, e + 0x20)                     // microseconds since year 1 (CellRtcTick)
                    val secs = ticks / 1_000_000 - 62_135_596_800L
                    out[id] = if (secs in 946_684_800L..4_102_444_800L) secs else 0L
                }
            }
            notes += "$path (${b.size} B): $sum -> ${out.size} unlocked"
            if (out.isNotEmpty()) return out
            notes += "table 6 gave no unlocked trophies; header: " + hex(b, 48)
        }
        return null
    }

    /**
     * TRPTITLE.DAT, as exported by the helper from the sealed trophy.img (layout checked against a real file of the user's console):
     * header 0x30 bytes (magic 8D 1F 8D 08, big-endian entry count at 8), then 0x20-byte index entries {type, entry size, 1, repeat count, u64 offset}.
     * Each data entry is a 16-byte header followed by its data. Type 0x800 (one entry per trophy, size 0x50): u32 trophy id, u32 unlocked (0/1),
     * u32 0x2000, u32 0, then two u64 times in microseconds since year 1 (unlock time first). Type 0x500: id, grade (1 P, 2 G, 3 S, 4 B), hidden.
     * Returns trophy id -> unlock time in seconds (an empty map = nothing unlocked), or null when the file has no 0x800 table.
     */
    internal fun parseTrpTitle(b: ByteArray, notes: MutableList<String>): Map<Int, Long>? {
        if (b.size < 0x30 || be32(b, 0) != 0x8D1F8D08L) { notes += "TRPTITLE.DAT: unknown header: " + hex(b, 32); return null }
        val n = be32(b, 8).toInt()
        if (n !in 1..64 || 0x30 + n * 0x20 > b.size) { notes += "TRPTITLE.DAT: odd table count $n"; return null }
        val out = HashMap<Int, Long>(); var total = 0
        for (t in 0 until n) {
            val o = 0x30 + t * 0x20
            val type = be32(b, o); val sz = be32(b, o + 4).toInt(); val cnt = be32(b, o + 12).toInt(); val off = be64(b, o + 16)
            if (type != 0x800L || sz < 0x20 || sz > 0x400 || cnt !in 1..4000) continue
            for (i in 0 until cnt) {
                val e = off + i.toLong() * (sz + 0x10) + 0x10
                if (e < 0 || e + 0x20 > b.size) break
                val p = e.toInt()
                total++
                val id = be32(b, p).toInt()
                if (be32(b, p + 4) == 0L) continue
                val secs = be64(b, p + 0x10) / 1_000_000L - 62_135_596_800L      // microseconds since year 1
                out[id] = if (secs in 946_684_800L..4_102_444_800L) secs else 0L
            }
        }
        if (total == 0) { notes += "TRPTITLE.DAT has no per-trophy table (type 0x800)"; return null }
        return out
    }

    /** Progress exported earlier by the helper (SealedTrophy) for this account and game, or null when it was never exported. */
    private fun readExport(ps4: Ps4, account: Account, npwr: String, notes: MutableList<String>): Map<Int, Long>? {
        if (npwr.isBlank()) return null
        val path = "/data/ezremote-client/trophy_export/${account.id}_$npwr/TRPTITLE.DAT"
        val b = try { Ps4Server.fsRead(ps4, path, 0, 1 shl 20, 10000) } catch (e: Exception) { return null }
        if (b.isEmpty()) return null
        val m = parseTrpTitle(b, notes) ?: return null
        notes += "From the sealed trophy image (copied earlier by the helper; use the button below to refresh): ${m.size} unlocked"
        return m
    }

    /** Unlocked trophies of [account] for the game with NPWR id [npwr]. */
    fun state(ps4: Ps4, account: Account, npwr: String, cache: File, gameTitle: String = ""): TrophyState {
        run {
            val n = ArrayList<String>()
            val opened = BooleanArray(1)
            val r = accountDb(ps4, account, npwr, gameTitle, cache, n, opened)
            if (r != null) return TrophyState(r.trophies.filter { it.unlocked }.associate { it.id to it.time }, n, r)
            // not synced with PSN: look for the account's own progress file of this game
            runCatching { readUsr(ps4, account, npwr, n) }.getOrNull()?.let { return TrophyState(it, n) }
            runCatching { readExport(ps4, account, npwr, n) }.getOrNull()?.let { return TrophyState(it, n) }
            // the database was read and simply has no entry for this game: this account has not played it (0%), no need to scan other files
            if (opened[0]) return TrophyState(emptyMap(), n)
            // otherwise fall through to the generic search below; keep the reason
            genericNotes[account.id] = n
        }
        val notes = ArrayList<String>(genericNotes[account.id].orEmpty()); val dbs = ArrayList<Pair<String, FsEntry>>()
        walkDbs(ps4, "/user/home/${account.id}/trophy", 0, dbs, notes)
        if (dbs.isEmpty()) { notes += "No trophy database found for this account."; return TrophyState(null, notes) }
        val key = npwr.substringBefore('_').removePrefix("NPWR")
        val ordered = dbs.sortedByDescending { it.first.contains("NPWR$key", true) }
        val unlocked = HashMap<Int, Long>(); var any = false
        for ((p, e) in ordered) {
            val f = localCopy(ps4, p, e, cache) ?: run { notes += "$p: could not be copied"; null } ?: continue
            val perTitle = p.contains("NPWR$key", true)
            try {
                val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    val tables = ArrayList<String>()
                    db.rawQuery("select name from sqlite_master where type='table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
                    for (t in tables) {
                        val cols = db.rawQuery("select * from \"$t\" limit 0", null).use { it.columnNames.toList() }
                        val low = cols.map { it.lowercase() }
                        fun pick(f2: (String) -> Boolean) = cols.getOrNull(low.indexOfFirst(f2)).takeIf { low.any(f2) }
                        val idCol = pick { it in listOf("trophy_id", "trop_id", "trophyid", "trophy_no", "trophy_num", "id", "no") } ?: continue
                        val flagCol = pick { it.contains("unlock") || it.contains("earn") || it.contains("achiev") || it == "flag" || it == "status" }
                        val tsCol = pick { it.contains("time") || it.contains("date") }
                        if (flagCol == null && tsCol == null) continue
                        val titleCol = pick { it.contains("npcomm") || it.contains("np_comm") || it.contains("np_com") || it.contains("npwr") || it == "title_id" || it.contains("communication") }
                        val where = when { titleCol != null -> " where cast(\"$titleCol\" as text) like '%NPWR$key%'"; perTitle -> ""; else -> continue }
                        val sel = listOfNotNull("\"$idCol\"", flagCol?.let { "\"$it\"" }, tsCol?.let { "\"$it\"" }).joinToString()
                        db.rawQuery("select $sel from \"$t\"$where", null).use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0)?.toIntOrNull() ?: continue
                                var i = 1
                                val flagOk = if (flagCol != null) { val s = c.getString(i++); s != null && s != "0" && s.isNotBlank() } else true
                                val ts = if (tsCol != null) c.getString(i)?.toLongOrNull() ?: 0L else 0L
                                any = true
                                if (flagOk && (flagCol != null || ts > 0)) unlocked[id] = toSeconds(ts)
                            }
                        }
                    }
                } finally { db.close() }
            } catch (ex: Exception) { notes += "$p: ${ex.message}" }
            if (any && unlocked.isNotEmpty()) break
        }
        if (!any) { notes += "Found ${dbs.size} database file(s) (${dbs.take(4).joinToString { it.first.substringAfterLast('/') }}) but none matched the expected trophy layout."; return TrophyState(null, notes) }
        return TrophyState(unlocked, notes)
    }

    fun points(g: Char) = when (g) { 'P' -> 300; 'G' -> 90; 'S' -> 30; 'B' -> 15; else -> 0 }
}
