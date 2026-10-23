package com.abdo.ps4monitor
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The server that ships inside the app (assets/server.elf, version in assets/server.version). */
object BundledServer {
    fun version(ctx: Context): String = try { ctx.assets.open("server.version").bufferedReader().use { it.readText().trim() } } catch (e: Exception) { "" }
    /** Copies the bundled ELF to the file the app sends (and re-sends by itself). Returns null on success, otherwise an error text. */
    fun install(ctx: Context): String? = try {
        val out = ServerLoader.elfFile(ctx)
        ctx.assets.open("server.elf").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        null
    } catch (e: Exception) { "Could not unpack the bundled server: " + (e.message ?: e.javaClass.simpleName) }
}

/**
 * Builds a PS4 project from a zip on GitHub Actions (same flow as the PS4 Builder app): one commit with the zip, wait for the workflow run,
 * read the release it publishes (status.json, errors.txt, the .elf) and download the ELF.
 */
object ServerBuilder {
    class Result(val elf: File?, val message: String, val runUrl: String = "", val errors: String = "")

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) ?: "" }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private fun friendly(e: Exception): String = when (e) {
        is GitHubException -> when (e.code) {
            401 -> tr("GitHub rejected the built-in token (401): it is invalid or expired.", "رفض GitHub الرمز المدمج (401): غير صالح أو منتهٍ.")
            403 -> tr("Forbidden (403): ${e.message}", "ممنوع (403): ${e.message}")
            404 -> tr("Not found (404): ${e.message}", "غير موجود (404): ${e.message}")
            else -> tr("GitHub error (${e.code}): ${e.message}", "خطأ من GitHub (${e.code}): ${e.message}")
        }
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            tr("No internet connection or timeout.", "لا يوجد اتصال بالإنترنت أو انتهت المهلة.")
        else -> tr("Unexpected error: ", "خطأ غير متوقع: ") + e.javaClass.simpleName + " " + (e.message ?: "")
    }

    suspend fun build(ctx: Context, uri: Uri, onStage: (String, Float?) -> Unit): Result {
        val (o, r) = BuildBackend.REPO.split("/")
        val api = GitHubApi(BuildBackend.token, o, r)
        var runId = 0L
        try {
            val name = displayName(ctx, uri)
            if (!name.lowercase(Locale.ROOT).endsWith(".zip")) return Result(null, tr("The chosen file is not a .zip ($name)", "الملف المختار ليس بصيغة .zip ($name)"))
            onStage(tr("Reading the file…", "جارٍ قراءة الملف…"), null)
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                ?: return Result(null, tr("Could not read the file.", "تعذّرت قراءة الملف."))
            if (bytes.size > 60 * 1024 * 1024) return Result(null, tr("The file is larger than 60 MB (upload limit).", "الملف أكبر من 60MB (حد الرفع)."))
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            onStage(tr("Uploading to the build repository…", "جارٍ الرفع إلى مستودع البناء…"), null)
            val sha = withContext(Dispatchers.IO) { api.commitFile("incoming/$stamp-$safe", bytes, "build: $name") }

            onStage(tr("Waiting for the build to start…", "بانتظار بدء البناء…"), null)
            var run: RunInfo? = null
            for (i in 0 until 30) {
                run = withContext(Dispatchers.IO) { api.findRun(sha) }
                if (run != null) break
                delay(5000)
            }
            val started = run ?: return Result(null, tr("The file was uploaded but GitHub started no build (commit ${sha.take(7)}).", "رُفع الملف لكن GitHub لم يبدأ أي بناء (commit ${sha.take(7)})."))
            runId = started.id

            var failures = 0
            var last: RunInfo = started
            while (true) {
                try {
                    val cur = withContext(Dispatchers.IO) { api.getRun(runId) }
                    last = cur
                    val steps = withContext(Dispatchers.IO) { api.getSteps(runId) }
                    failures = 0
                    val done = steps.count { it.status == "completed" }
                    val stage = steps.firstOrNull { it.status == "in_progress" }?.name ?: steps.lastOrNull { it.status == "completed" }?.name ?: ""
                    onStage((if (cur.status == "queued") tr("Queued on GitHub…", "في الانتظار على GitHub…") else tr("Building: ", "جارٍ البناء: ")) + stage,
                        if (steps.isEmpty()) null else done.toFloat() / steps.size)
                    if (cur.status == "completed") break
                } catch (e: CancellationException) { throw e
                } catch (e: GitHubException) { if (e.code == 401 || e.code == 403 || e.code == 404 || ++failures > 5) throw e
                } catch (e: Exception) { if (++failures > 5) throw e }
                delay(5000)
            }
            runId = 0L
            if (last.conclusion == "cancelled") return Result(null, tr("The build was cancelled.", "أُلغي البناء."), last.url)

            val tag = "build-${last.number}"
            onStage(tr("Fetching the results…", "جلب النتائج…"), null)
            var release = withContext(Dispatchers.IO) { api.getRelease(tag) }
            var tries = 0
            while (release == null && tries++ < 5) { delay(3000); release = withContext(Dispatchers.IO) { api.getRelease(tag) } }
            if (release == null) return Result(null, tr("The build ended with '${last.conclusion}' and published no release ($tag). Open the run page for details.",
                "انتهى البناء بنتيجة '${last.conclusion}' دون إصدار ($tag). افتح صفحة التشغيل للتفاصيل."), last.url)
            val status = release.assets.firstOrNull { it.name == "status.json" }?.let { a -> JSONObject(withContext(Dispatchers.IO) { api.downloadText(a.id) }) }
            val ok = status?.optBoolean("ok", false) ?: false
            val errors = release.assets.firstOrNull { it.name == "errors.txt" }?.let { a -> withContext(Dispatchers.IO) { api.downloadText(a.id) } } ?: ""
            val asset = release.assets.firstOrNull { it.name.endsWith(".elf") }
            if (!ok || asset == null) return Result(null, tr("The build failed: the project did not compile.", "فشل البناء: لم يُترجم المشروع."), last.url, errors)
            val dir = File(ctx.filesDir, "built").apply { mkdirs() }
            val dest = File(dir, "$tag-${asset.name}")
            withContext(Dispatchers.IO) { api.downloadTo(asset.id, dest) { got -> onStage(tr("Downloading the ELF…", "تنزيل ملف ELF…"), if (asset.size > 0) got.toFloat() / asset.size else null) } }
            return Result(dest, tr("Built ${asset.name} (${Fmt.bytes(dest.length())}).", "تم بناء ${asset.name} (${Fmt.bytes(dest.length())})."), last.url)
        } catch (e: CancellationException) {
            if (runId != 0L) withContext(NonCancellable) { try { withContext(Dispatchers.IO) { api.cancelRun(runId) } } catch (x: Exception) { } }
            throw e
        } catch (e: Exception) {
            if (runId != 0L) withContext(NonCancellable) { try { withContext(Dispatchers.IO) { api.cancelRun(runId) } } catch (x: Exception) { } }
            return Result(null, friendly(e))
        }
    }
}
