package com.abdo.ps4monitor
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * One-tap saving of PS4 screenshots / videos into the phone's gallery, like a messaging app does: every item is written through MediaStore into
 * DCIM/[ALBUM], so the gallery shows ONE album with that name holding both the photos and the videos, at the original quality (the bytes are copied
 * unchanged from the PS4). Android 10+ needs no permission for this; Android 8/9 needs WRITE_EXTERNAL_STORAGE (the UI asks for it).
 * The work runs in this object's own scope, so leaving the screen does not cancel it (the process must stay alive: keep the app open for long videos).
 */
object CaptureSaver {
    /** Album (folder) name in the gallery. */
    const val ALBUM = "PS4 Monitor"
    class State(val key: String, val name: String, val done: Int, val total: Int, val fraction: Float)

    /** The item being copied right now (null = idle). */
    val state = MutableStateFlow<State?>(null)
    /** Keys of items waiting or being copied. */
    val busy = MutableStateFlow<Set<String>>(emptySet())
    /** key -> content:// uri of what was saved (remembered across restarts). */
    val saved = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Last result / problem sentence for the UI. */
    val message = MutableStateFlow<String?>(null)

    private const val SEP = "\u0001"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = ArrayDeque<Pair<Ps4, Capture>>()
    private var running = false
    private var total = 0
    @Volatile private var done = 0
    private var ok = 0
    private var bad = 0
    private var lastErr = ""

    fun init(c: Context) {
        app = c.applicationContext
        val m = HashMap<String, String>()
        (Store.sp.getStringSet("saved_caps", emptySet()) ?: emptySet()).forEach { e ->
            val i = e.indexOf(SEP); if (i > 0) m[e.substring(0, i)] = e.substring(i + 1)
        }
        saved.value = m
    }
    private fun persist() { Store.sp.edit().putStringSet("saved_caps", saved.value.map { "${it.key}$SEP${it.value}" }.toSet()).apply() }

    fun key(ps4: Ps4, c: Capture) = "${ps4.id}|${c.path}|${c.size}"
    fun say(s: String?) { message.value = s }
    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Queues the items that are not saved yet; copying starts at once and goes on one item at a time. */
    fun enqueue(ps4: Ps4, list: List<Capture>) {
        var added = 0; var start = false
        synchronized(lock) {
            for (c in list) {
                val k = key(ps4, c)
                if (saved.value.containsKey(k) || busy.value.contains(k)) continue
                pending.addLast(ps4 to c); busy.update { it + k }; added++
            }
            if (added > 0) {
                if (!running) { running = true; start = true; total = 0; done = 0; ok = 0; bad = 0; lastErr = ""; message.value = null }
                total += added
            }
        }
        if (start) scope.launch { work() }
    }

    private fun work() {
        while (true) {
            var summary: String? = null
            val job = synchronized(lock) {
                val j = pending.removeFirstOrNull()
                if (j == null) { running = false; summary = summaryText() }
                j
            }
            if (job == null) { state.value = null; message.value = summary; break }
            val (ps4, c) = job
            val k = key(ps4, c)
            state.value = State(k, c.name, done, total, 0f)
            try {
                val uri = saveOne(ps4, c) { f -> state.value = State(k, c.name, done, total, f) }
                saved.update { it + (k to uri.toString()) }; persist(); ok++
            } catch (e: Exception) { bad++; lastErr = e.message ?: e.javaClass.simpleName }
            busy.update { it - k }
            done++
        }
    }
    private fun summaryText(): String =
        if (bad == 0) tr("Saved $ok item(s) to the “$ALBUM” album.", "حُفظ $ok عنصر في ألبوم «$ALBUM».")
        else tr("Saved $ok, failed $bad: $lastErr", "حُفظ $ok وفشل $bad: $lastErr")

    private fun mimeOf(name: String, video: Boolean): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"
        else -> if (video) "video/mp4" else "image/jpeg"
    }
    private fun displayName(c: Capture): String {
        val game = (Ps4Info.names[c.titleId] ?: c.titleId).replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(60)
        return if (game.isBlank() || c.name.contains(game, true)) c.name else "$game - ${c.name}"
    }
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); if (!f.exists()) return f
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)" + if (ext.isEmpty()) "" else ".$ext"); n++ }
        return f
    }

    @Suppress("DEPRECATION")
    private fun saveOne(ps4: Ps4, c: Capture, progress: (Float) -> Unit): Uri {
        val cr = app.contentResolver
        val size = if (c.size > 0) c.size else Ps4Server.fsSize(ps4, c.path, 6000)
        if (size <= 0) throw IOException(tr("The file is empty or could not be read.", "الملف فارغ أو تعذّرت قراءته."))
        val mime = mimeOf(c.name, c.video)
        val name = displayName(c)
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (c.mtime > 0) values.put(if (c.video) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN, c.mtime)
        var legacyPath: String? = null
        val collection: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$ALBUM")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            collection = if (c.video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                         else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM); dir.mkdirs()
            val f = uniqueFile(dir, name)
            legacyPath = f.path
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            values.put(MediaStore.MediaColumns.DATA, f.path)
            collection = if (c.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = cr.insert(collection, values) ?: throw IOException(tr("The phone refused to create the file.", "رفض الهاتف إنشاء الملف."))
        try {
            val os = cr.openOutputStream(uri) ?: throw IOException(tr("Could not open the destination file.", "تعذّر فتح ملف الوجهة."))
            os.use { Ps4Server.fsCopyTo(ps4, c.path, size, it) { d -> progress((d.toDouble() / size).toFloat().coerceIn(0f, 1f)) } }
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues(); v.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, v, null, null)
            } else if (legacyPath != null) MediaScannerConnection.scanFile(app, arrayOf(legacyPath), arrayOf(mime), null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun exists(uri: Uri): Boolean = try {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) { false }

    /** Opens the saved copy in the phone's gallery / video player. If the user deleted it from the gallery, it is forgotten so it can be saved again. */
    fun open(ctx: Context, ps4: Ps4, c: Capture) {
        val k = key(ps4, c); val u = saved.value[k] ?: return
        val uri = Uri.parse(u)
        if (!exists(uri)) {
            saved.update { it - k }; persist()
            message.value = tr("This file was deleted from the gallery. Tap Save to save it again.", "حُذف هذا الملف من المعرض. اضغط حفظ لحفظه من جديد.")
            return
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(c.name, c.video))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { message.value = tr("No app can open this file.", "لا يوجد تطبيق يفتح هذا الملف.") }
    }
}
