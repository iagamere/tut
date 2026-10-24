package com.abdo.ps4monitor

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * EXPERIMENTAL. A game that is not synced with PSN keeps its trophy progress only inside the encrypted image
 * /user/home/<account>/trophy/data/<NPWR>/trophy.img (key: the sibling file "sealedkey", sealed with the console's hardware key).
 * The bundled helper payload (assets/trophymount.elf, source in payload/trophymount) asks the console to open that image the way
 * the open-source tool Apollo does and copies the files inside to /data/ezremote-client/trophy_export/<account>_<NPWR>/.
 * This object sends the helper, waits, brings the server back if the loader replaced it, and reads the helper's report.
 * NOT run on any console yet: the helper may simply be refused (it changes no process credentials); the report says which step failed.
 */
object SealedTrophy {
    private const val BASE = "/data/ezremote-client"
    class Result(val notes: List<String>, val hex: String, val ok: Boolean)

    private fun helperFile(ctx: Context): File {
        val f = File(ctx.cacheDir, "trophymount.elf")
        ctx.assets.open("trophymount.elf").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }
    private fun serverFile(ctx: Context): File {
        val own = ServerLoader.elfFile(ctx)
        if (own.exists() && own.length() > 1024) return own
        val f = File(ctx.cacheDir, "server-bundled.elf")
        ctx.assets.open("server.elf").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }
    private fun readText(ps4: Ps4, path: String, max: Int): String? = try {
        val sz = Ps4Server.fsSize(ps4, path, 4000)
        String(Ps4Server.fsRead(ps4, path, 0, sz.coerceIn(0L, max.toLong()).toInt(), 8000), Charsets.UTF_8)
    } catch (e: Exception) { null }
    private fun isDone(ps4: Ps4): Boolean = try { Ps4Server.fsRead(ps4, "$BASE/trophy_helper_done.txt", 0, 64, 3000).isNotEmpty() } catch (e: Exception) { false }

    private fun collect(ps4: Ps4, dir: String, depth: Int, out: MutableList<Pair<String, Long>>) {
        val l = try { Ps4Server.fsList(ps4, dir, 6000) } catch (e: Exception) { return }
        for (e in l) {
            val p = "$dir/${e.name}"
            if (e.isDir) { if (depth < 3) collect(ps4, p, depth + 1, out) } else if (e.name != "_result.txt") out += p to e.size
        }
    }

    suspend fun run(ctx: Context, ps4: Ps4, acct: String, npwr: String, onStage: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val name = "${acct}_$npwr"
        onStage(tr("Preparing the request…", "جارٍ تجهيز الطلب…"))
        Ps4Api.createFolder(ps4, "$BASE/trophy_req")
        val mk = Ps4Api.createFolder(ps4, "$BASE/trophy_req/$name")
        if (!mk.ok) { notes += "Could not create the request folder: ${mk.message}"; return@withContext Result(notes, "", false) }
        try { Ps4Server.fsOp(ps4, "delete", JSONObject().put("path", "$BASE/trophy_helper_done.txt")) } catch (e: Exception) { }
        onStage(tr("Sending the helper to the PS4…", "جارٍ إرسال الأداة المساعدة إلى الـPS4…"))
        val err = ServerLoader.send(ps4.host, helperFile(ctx)) { }
        if (err != null) { notes += "Could not send the helper: $err"; return@withContext Result(notes, "", false) }
        var done = false
        for (i in 1..15) {
            onStage(tr("Waiting for the helper (", "بانتظار الأداة المساعدة (") + i + "/15)…")
            delay(2000)
            if (isDone(ps4)) { done = true; break }
        }
        var serverBack = true
        if (!done && Ps4Server.info(ps4, 2500) == null) {
            notes += "The server stopped answering after the helper was sent (the loader may replace the running server); sending it again."
            onStage(tr("Bringing the server back…", "جارٍ إعادة تشغيل السيرفر…"))
            val r = ServerSwitch.sendAndWait(ps4, serverFile(ctx)) { _, _ -> }
            serverBack = r.first
            notes += "Server: " + r.second.lines().firstOrNull().orEmpty()
            if (serverBack) done = isDone(ps4)
        }
        if (!serverBack) { notes += "The server did not come back, so the report cannot be read now."; return@withContext Result(notes, "", false) }
        onStage(tr("Reading the report…", "جارٍ قراءة التقرير…"))
        val dir = "$BASE/trophy_export/$name"
        val rep = readText(ps4, "$dir/_result.txt", 20000)
        if (rep != null) notes += rep.lines().filter { it.isNotBlank() }.take(40)
        else {
            notes += if (done) "The helper finished but wrote no report for this game." else "The helper has not finished (or never started)."
            readText(ps4, "/data/ezremote_trophy.log", 20000)?.let { notes += "Helper log:"; notes += it.lines().filter { l -> l.isNotBlank() }.take(40) }
        }
        val ok = rep?.contains("RESULT: OK") == true
        val files = ArrayList<Pair<String, Long>>()
        if (ok) collect(ps4, dir, 0, files)
        val hex = StringBuilder()
        for ((p, size) in files.take(20)) {
            notes += "$p: $size B"
            if (size in 1..65536) {
                val b = try { Ps4Server.fsRead(ps4, p, 0, size.toInt(), 15000) } catch (e: Exception) { ByteArray(0) }
                hex.append("### ").append(p.removePrefix("$dir/")).append(" (").append(size).append(" B)\n")
                b.forEachIndexed { k, v -> hex.append("%02X".format(v)); if (k % 32 == 31) hex.append('\n') else hex.append(' ') }
                hex.append('\n')
            }
        }
        Result(notes, hex.toString(), ok)
    }
}

/** Button + result of the experimental sealed-image reader, shown under a game's trophies when its progress could not be read. */
@Composable fun SealedTrophyPanel(ps4: Ps4, acct: String, npwr: String, onDone: suspend () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<SealedTrophy.Result?>(null) }
    Panel {
        SectionTitleHelp(tr("Read the sealed trophy file (experimental)", "قراءة ملف الإنجازات المشفّر (تجريبي)"),
            tr("This game's progress is inside an encrypted image on the PS4. This sends a small helper to the PS4 that asks the console to open it, copies the plain files out, and shows what it found. It has never been tried on a console and may be refused; the report says which step failed. The server may restart during the process.",
               "تقدّم هذه اللعبة داخل صورة مشفّرة على الـPS4. هذا الزر يرسل أداة صغيرة إلى الـPS4 تطلب من النظام فتحها، وتنسخ الملفات العادية منها، ثم تعرض ما وجدته. لم تُجرَّب أبداً على جهاز وقد تُرفض؛ والتقرير يبيّن أي خطوة فشلت. قد يُعاد تشغيل السيرفر أثناء العملية."))
        Dim(tr("Needs GoldHEN's payload loader (port 9090). Nothing is changed on the console's trophy data.", "يحتاج محمّل الـpayload في GoldHEN (المنفذ 9090). لا يتم تغيير أي شيء في بيانات الإنجازات."))
        Button(enabled = !busy, onClick = {
            busy = true; res = null
            scope.launch { res = try { SealedTrophy.run(ctx, ps4, acct, npwr) { stage = it } } catch (e: Exception) { SealedTrophy.Result(listOf("Failed: ${e.message}"), "", false) }; if (res?.ok == true) { try { onDone() } catch (e: Exception) { } }; busy = false; stage = "" }
        }) { Lbl(tr("Try to read it", "جرّب القراءة")) }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(stage) }
        res?.let { r ->
            r.notes.take(40).forEach { Dim(it) }
            TextButton(onClick = { clip.setText(AnnotatedString(r.notes.joinToString("\n") + "\n\n" + r.hex)) }) { Lbl(tr("Copy report and files (hex)", "نسخ التقرير والملفات (hex)")) }
        }
    }
}

/** Asks the server (abdo17 and later) to export every game's sealed trophy progress; the server skips images that did not change. */
object TrophySync {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Blocking. Returns the final status, or null when the server cannot do it or a sync already ran in the last 2 minutes (unless [force]). */
    fun ensure(ps4: Ps4, force: Boolean, onStage: (String) -> Unit): Ps4Server.TrophyStatus? {
        val now = System.currentTimeMillis()
        if (!force && now - (last[ps4.id] ?: 0L) < 120_000L) return null
        var st = Ps4Server.trophySync(ps4, force) ?: return null
        last[ps4.id] = now
        var waited = 0
        while (st.running && waited < 120_000) {
            onStage(tr("Updating trophy data on the console…", "جارٍ تحديث بيانات الإنجازات من الجهاز…") + " (" + st.done + "/" + st.total + ")")
            Thread.sleep(1500); waited += 1500
            st = Ps4Server.trophyStatus(ps4) ?: break
        }
        last[ps4.id] = System.currentTimeMillis()
        return st
    }
}

/** Shown instead of the experimental helper panel when the server does the sync itself. */
@Composable fun TrophyRefreshPanel(ps4: Ps4, onDone: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    Panel {
        SectionTitleHelp(tr("Trophy data from the console", "بيانات الإنجازات من الجهاز"),
            tr("Games that were never synced with PSN keep their progress in an encrypted file on the PS4. The server opens those files itself and the app reads the result. This runs by itself when you open a game's trophies; use the button to force a fresh copy after you earned something new.",
               "الألعاب التي لم تُزامَن مع PSN تحفظ تقدّمها في ملف مشفّر على الـPS4. السيرفر يفتح هذه الملفات بنفسه والتطبيق يقرأ النتيجة. يحدث هذا تلقائياً عند فتح إنجازات أي لعبة؛ استخدم الزر لفرض نسخة جديدة بعد أن تحقق إنجازاً جديداً."))
        Button(enabled = !busy, onClick = {
            busy = true; report = emptyList()
            scope.launch {
                val r = withContext(Dispatchers.IO) { try { TrophySync.ensure(ps4, true) { stage = it } } catch (e: Exception) { null } }
                report = r?.log?.takeLast(8) ?: listOf(tr("The server did not answer the trophy request.", "لم يستجب السيرفر لطلب الإنجازات."))
                try { onDone() } catch (e: Exception) { }
                busy = false; stage = ""
            }
        }) { Lbl(tr("Refresh from the console", "تحديث من الجهاز")) }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(stage) }
        report.forEach { Dim(it) }
    }
}
