@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Best-effort copy of the server file to /data/payloads/ezremote-server.elf through GoldHEN's FTP server, done after a successful send so that
 * GoldHEN's AutoRun can use it. It never changes the result of the send: a failure is only reported as a note. NOT verified against GoldHEN.
 */
object AutoPlace {
    fun enabled(): Boolean = try { Store.sp.getBoolean("auto_place", true) } catch (e: Exception) { true }
    fun setEnabled(on: Boolean) { try { Store.sp.edit().putBoolean("auto_place", on).apply() } catch (e: Exception) { } }
    fun port(): Int = try { (Store.sp.getString("ftp_port", "2121") ?: "2121").toIntOrNull()?.takeIf { it in 1..65535 } ?: 2121 } catch (e: Exception) { 2121 }
    fun setPort(p: String) { try { Store.sp.edit().putString("ftp_port", p).apply() } catch (e: Exception) { } }
    /** null = placed, otherwise a short reason. */
    fun place(host: String, file: File): String? = try {
        file.inputStream().use { FtpUpload.upload(host, port(), "/data/payloads", "ezremote-server.elf", it, file.length(), null) }
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
}

/** Sending a server ELF to GoldHEN and checking that it really took over. */
object ServerSwitch {
    /** (true, version) when the custom server answers afterwards; (false, reason) otherwise. */
    suspend fun sendAndWait(ps4: Ps4, file: File, onStage: (String, Float?) -> Unit): Pair<Boolean, String> {
        onStage(tr("Sending to the PS4…", "جارٍ الإرسال إلى الـPS4…"), 0f)
        val err = withContext(Dispatchers.IO) { ServerLoader.send(ps4.host, file) { p -> onStage(tr("Sending to the PS4…", "جارٍ الإرسال إلى الـPS4…"), p / 100f) } }
        if (err != null) {
            val rep = withContext(Dispatchers.IO) { ServerLoader.portReport(ps4.host) }
            return false to (tr("Could not send: ", "تعذّر الإرسال: ") + err + "\n" + rep + "\n" +
                tr("Check that \"Enable PayLoader Server\" is on in GoldHEN settings.", "تأكد أن «Enable PayLoader Server» مفعّل في إعدادات GoldHEN."))
        }
        // a custom server may already be running: remember its version so the OLD one answering is not mistaken for the new one
        val before = withContext(Dispatchers.IO) { Ps4Server.info(ps4, 2500) }
        val beforeVer: String? = if (before != null && before.custom && !before.locked) before.version else null
        Ps4Server.forget(ps4)
        var resent = false; var sawDown = false
        for (i in 1..14) {
            onStage(tr("Waiting for the server to start (", "بانتظار تشغيل السيرفر (") + i + "/14)…", null)
            delay(3000)
            val r = withContext(Dispatchers.IO) { Ps4Server.info(ps4, 2500) }
            if (r == null || !r.custom) sawDown = true
            if (r != null && r.custom) {
                // new server = no custom server was running before, or its version differs, or the old one went away and a server came back
                // (same version re-sent: accepted after ~18 s)
                if (beforeVer == null || r.locked || r.version != beforeVer || sawDown || i >= 6) {
                    // first run on a clean console: make sure the server's own data folder exists (best effort, never blocks)
                    withContext(Dispatchers.IO) { try { if (!r.locked) { Ps4Server.setFsAccess(ps4, true); Ps4Api.createFolder(ps4, "/data/ezremote-client") } } catch (e: Exception) { } }
                    val ok = if (r.locked) tr("token required", "يطلب رمزًا") else r.version
                    if (!AutoPlace.enabled()) return true to ok
                    onStage(tr("Placing a copy in /data/payloads…", "جارٍ وضع نسخة في /data/payloads…"), null)
                    val placed = withContext(Dispatchers.IO) { AutoPlace.place(ps4.host, file) }
                    return true to (ok + "\n" + if (placed == null) tr("A copy was also placed in /data/payloads (for GoldHEN AutoRun).", "وُضعت أيضًا نسخة في /data/payloads (لتشغيل AutoRun في GoldHEN).")
                        else tr("Could not place a copy in /data/payloads: ", "تعذّر وضع نسخة في /data/payloads: ") + placed + " " + tr("(turn on FTP in GoldHEN if you want this)", "(فعّل FTP في GoldHEN إن أردت ذلك)"))
                }
            }
            // the old server still answers (HTTP 404 on /v2/info): after ~18 s send the file once more
            if (!resent && i >= 6 && r != null && !r.custom) {
                resent = true
                withContext(Dispatchers.IO) { ServerLoader.send(ps4.host, file) { } }
            }
        }
        return false to (tr("The PS4 received the file but the new server did not answer. Wait a minute and refresh; if it persists, read /data/ezremote_boot.log on the PS4.",
            "استلم الـPS4 الملف لكن السيرفر الجديد لم يستجب. انتظر دقيقة ثم حدّث؛ وإن استمر فاقرأ الملف /data/ezremote_boot.log في الـPS4."))
    }
}

@Composable private fun ChoiceRow(title: String, subtitle: String, badge: String?, enabled: Boolean, onClick: () -> Unit) =
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (badge != null) AssistChip(onClick = {}, label = { Lbl(badge, style = MaterialTheme.typography.labelSmall) }, enabled = false)
            }
            Dim(subtitle)
        }
    }

/** Bottom sheet opened from the server line on the Home screen: our bundled server / a server built from the user's zip. */
@Composable fun ServerPickerSheet(ps4: Ps4, info: Ps4Server.ServerInfo?, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val bundled = remember { BundledServer.version(ctx) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    val busyNow = rememberUpdatedState(busy)
    var stage by remember { mutableStateOf("") }
    var prog by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    val customNow = info?.custom == true
    val runningVersion = if (customNow) info?.version.orEmpty() else ""

    fun finish(ok: Boolean, text: String) { message = text; stage = ""; prog = null; if (ok) onChanged() }

    /** Runs [block] as the one running job; the busy flag is Compose state so cancelling also updates the screen. */
    fun launchJob(block: suspend () -> Unit) {
        message = ""; details = ""; busy = true
        job = scope.launch { try { block() } finally { busy = false; stage = ""; prog = null } }
    }

    fun useBundled() {
        launchJob {
            val err = withContext(Dispatchers.IO) { BundledServer.install(ctx) }
            if (err != null) return@launchJob finish(false, err)
            val (ok, m) = ServerSwitch.sendAndWait(ps4, ServerLoader.elfFile(ctx)) { s, p -> stage = s; prog = p }
            finish(ok, if (ok) tr("The custom server is running (", "السيرفر المخصص يعمل (") + m + ")" else m)
        }
    }
    fun buildFromZip(uri: Uri) {
        launchJob {
            val r = ServerBuilder.build(ctx, uri) { s, p -> stage = s; prog = p }
            val elf = r.elf
            if (elf == null) { details = (r.errors.takeLast(1500) + (if (r.runUrl.isNotBlank()) "\n" + r.runUrl else "")).trim(); return@launchJob finish(false, r.message) }
            // the built file becomes the one the app sends (and re-sends by itself) from now on
            withContext(Dispatchers.IO) { elf.copyTo(ServerLoader.elfFile(ctx), overwrite = true) }
            val (ok, m) = ServerSwitch.sendAndWait(ps4, ServerLoader.elfFile(ctx)) { s, p -> stage = s; prog = p }
            finish(ok, if (ok) r.message + "\n" + tr("Your server is running (", "سيرفرك يعمل (") + m + ")" else r.message + "\n" + m)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) buildFromZip(uri) }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { v -> v != SheetValue.Hidden || !busyNow.value })) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Choose the server", "اختر السيرفر"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Dim(ps4.name + "  •  " + ps4.host)
            ChoiceRow(tr("Our server", "سيرفرنا") + if (bundled.isNotBlank()) " ($bundled)" else "",
                tr("Built into this app. Pause, resume, priorities, queue, file access, PS4 information. Needs \"Enable PayLoader Server\" in GoldHEN.",
                    "مدمج في هذا التطبيق. إيقاف مؤقت واستئناف وأولويات وقائمة انتظار ووصول للملفات ومعلومات الـPS4. يحتاج «Enable PayLoader Server» في GoldHEN."),
                if (customNow && runningVersion.isNotBlank() && runningVersion.endsWith(bundled.substringAfterLast('-'))) tr("Running", "يعمل") else null, !busy && bundled.isNotBlank()) { useBundled() }
            ChoiceRow(tr("Build a server from my zip", "بناء سيرفر من ملف zip الخاص بي"),
                tr("Choose a PS4 project .zip. It is built online (GitHub Actions) and the resulting server is sent to the PS4. Needs internet.",
                    "اختر ملف .zip لمشروع PS4. يُبنى عبر الإنترنت (GitHub Actions) ثم يُرسل السيرفر الناتج إلى الـPS4. يحتاج إنترنت."),
                null, !busy) { picker.launch(arrayOf("*/*")) }

            if (busy) {
                Text(stage, fontWeight = FontWeight.SemiBold)
                val p = prog
                if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { job?.cancel() }) { Lbl(tr("Cancel", "إلغاء")) }
            }
            if (message.isNotBlank()) Text(message)
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (!busy) TextButton(onClick = onDismiss) { Lbl(tr("Close", "إغلاق")) }
        }
    }
}
