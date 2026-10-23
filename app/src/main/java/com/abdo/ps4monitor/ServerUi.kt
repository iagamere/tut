@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status of the server on the PS4, sending it (GoldHEN BinLoader), its download settings and its log. */
@Composable fun ServerScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val clip = LocalClipboardManager.current
    val activeId by Ps4Repo.activeId.collectAsState()
    val ps4List by Ps4Repo.list.collectAsState()
    val p = remember(activeId, ps4List) { Ps4Repo.active() }
    var tokenText by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Ps4Server.ServerInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var msg by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf<String?>(null) }
    val elf = remember { ServerLoader.elfFile(ctx) }
    var hasElf by remember { mutableStateOf(elf.exists()) }
    var ftpPort by remember { mutableStateOf(AutoPlace.port().toString()) }
    var autoPlace by remember { mutableStateOf(AutoPlace.enabled()) }
    var ftpBusy by remember { mutableStateOf(false) }
    var ftpProg by remember { mutableStateOf(0) }
    var ftpMsg by remember { mutableStateOf("") }

    fun refresh() {
        val pp = p ?: return
        scope.launch { busy = true; info = withContext(Dispatchers.IO) { Ps4Server.info(pp) }; checked = true; busy = false }
    }
    LaunchedEffect(p?.id) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val err = withContext(Dispatchers.IO) { ServerLoader.importElf(ctx, uri) }
            if (err != null) msg = Tx.t(err)
            else { hasElf = true; msg = tr("File saved in the app. Tap \"Send to PS4\".", "حُفظ الملف داخل التطبيق. اضغط «إرسال إلى PS4».") }
        }
    }

    /** Puts the server file in /data/payloads through GoldHEN's FTP server (nothing is started by this). */
    fun uploadAuto(useSaved: Boolean) {
        val pp = p ?: return
        val port = AutoPlace.port()
        scope.launch {
            ftpBusy = true; ftpProg = 0; ftpMsg = ""
            val err = withContext(Dispatchers.IO) {
                try {
                    val src: File = if (useSaved && elf.exists()) elf
                        else File(ctx.cacheDir, "ftp-server.elf").also { f -> ctx.assets.open("server.elf").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                    val r = src.inputStream().use { ins -> FtpUpload.upload(pp.host, port, "/data/payloads", "ezremote-server.elf", ins, src.length()) { v -> ftpProg = v } }
                    if (src !== elf) runCatching { src.delete() }
                    r
                } catch (e: Exception) { "Could not read the server file: " + (e.message ?: e.javaClass.simpleName) }
            }
            ftpMsg = if (err == null) tr("Done: /data/payloads/ezremote-server.elf is on the PS4. Now, once, on the PS4: GoldHEN menu > LaunchPad > highlight it > Square > Add to AutoRun queue.",
                    "تم: الملف /data/payloads/ezremote-server.elf موجود على الـPS4. الآن، مرة واحدة، على الـPS4: قائمة GoldHEN > LaunchPad > ظلّل الملف > Square > Add to AutoRun queue.")
                else tr("Failed: ", "فشل: ") + Tx.t(err)
            ftpBusy = false
        }
    }

    fun sendServer() {
        val pp = p ?: return
        scope.launch {
            busy = true; progress = 0; msg = ""
            val err = withContext(Dispatchers.IO) { ServerLoader.send(pp.host, elf) { v -> progress = v } }
            progress = -1
            if (err != null) { msg = tr("Could not send: ", "تعذّر الإرسال: ") + Tx.t(err); busy = false; return@launch }
            msg = tr("Sent. Waiting for the server to start…", "أُرسل. بانتظار تشغيل السيرفر…")
            Ps4Server.forget(pp)
            var found: Ps4Server.ServerInfo? = null
            for (i in 0 until 14) {
                delay(2000)
                val r = withContext(Dispatchers.IO) { Ps4Server.info(pp, 2000) }
                if (r != null && r.custom) { found = r; break }
            }
            info = found ?: withContext(Dispatchers.IO) { Ps4Server.info(pp) }
            msg = if (found != null) tr("The custom server is running (", "السيرفر المخصص يعمل (") + found.version + ")"
                  else tr("The PS4 received the file but the server did not answer. Watch the PS4 screen and check the log.", "استلم الـPS4 الملف لكن السيرفر لم يستجب. راقب شاشة الـPS4 وافحص السجل.")
            if (found != null && AutoPlace.enabled()) {
                val placed = withContext(Dispatchers.IO) { AutoPlace.place(pp.host, elf) }
                msg += "\n" + if (placed == null) tr("A copy was also placed in /data/payloads (for GoldHEN AutoRun).", "وُضعت أيضًا نسخة في /data/payloads (لتشغيل AutoRun في GoldHEN).")
                    else tr("Could not place a copy in /data/payloads: ", "تعذّر وضع نسخة في /data/payloads: ") + placed
            }
            busy = false
        }
    }
    fun push(json: String) {
        val pp = p ?: return
        scope.launch {
            val c = withContext(Dispatchers.IO) { Ps4Server.updateSettings(pp, json) }
            if (!c.ok) msg = tr("Could not change the setting: ", "تعذّر تغيير الإعداد: ") + Tx.t(c.message)
            info = withContext(Dispatchers.IO) { Ps4Server.info(pp) }
        }
    }
    fun showLog() {
        val pp = p ?: return
        scope.launch {
            busy = true
            logText = withContext(Dispatchers.IO) {
                try { if (info?.custom == true) Ps4Server.log(pp, 80) else Ps4Server.logFile(pp) }
                catch (e: Exception) { try { Ps4Server.logFile(pp) } catch (e2: Exception) { Ps4Api.friendly(e2) } }
            }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("PS4 server", "خادم PS4"), nav) { TextButton(onClick = { refresh() }, enabled = !busy) { Lbl(tr("Refresh", "تحديث")) } }
        if (p == null) { EmptyState(R.drawable.ic_console, tr("Add a PS4 first.", "أضف جهاز PS4 أولًا.")); return@Column }
        val i = info

        Panel {
            SectionTitleHelp(tr("Status", "الحالة"), tr("Shows whether our download server on the PS4 answers right now (port 6701). If it says not running, send the server again.", "يعرض هل يستجيب خادم التحميل الخاص بنا على الـPS4 الآن (المنفذ 6701). إن كان غير شغال فأعد إرسال السيرفر."))
            val line = when {
                !checked -> tr("Checking…", "جارٍ الفحص…")
                i == null -> tr("Not reachable on ${p.host}:6701", "غير متاح على ${p.host}:6701")
                i.locked -> tr("The server asks for a token. Enter it in this PS4's settings (Settings > edit PS4).", "الخادم يطلب رمزًا. أدخله في إعدادات هذا الجهاز (الإعدادات > تعديل PS4).")
                i.custom -> tr("Custom server ", "السيرفر المخصص ") + i.version
                else -> tr("Older PS4 server (no per-download control)", "خادم PS4 الأصلي (بلا تحكم بتحميل واحد)")
            }
            Text(line, fontWeight = FontWeight.SemiBold)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg)
        }

        Panel {
            SectionTitleHelp(tr("Send the server to the PS4", "إرسال السيرفر إلى الـPS4"), tr("Sends the server program to the PS4 through GoldHEN's PayLoader (port 9090). It replaces any server already running on the console. Needed after each restart of the PS4.", "يرسل برنامج السيرفر إلى الـPS4 عبر PayLoader في GoldHEN (المنفذ 9090). يستبدل أي سيرفر شغال على الجهاز. مطلوب بعد كل إعادة تشغيل للـPS4."))
            Dim(tr("Needs \"Enable PayLoader Server\" in GoldHEN settings (port 9090). Choose the .elf once; after that one tap sends it. Sending replaces the running PS4 server.",
                "يحتاج تفعيل «Enable PayLoader Server» في إعدادات GoldHEN (المنفذ 9090). اختر ملف .elf مرة واحدة، وبعدها ضغطة واحدة ترسله. الإرسال يستبدل خادم PS4 العامل."))
            Dim(if (hasElf) tr("Saved file: ${elf.length() / 1024} KB. The app re-sends it by itself if the server stops while downloads are unfinished.",
                    "الملف المحفوظ: ${elf.length() / 1024} KB. يعيد التطبيق إرساله تلقائيًا إذا توقف السيرفر وفيه تحميلات غير مكتملة.")
                else tr("No file chosen yet.", "لم يُختر ملف بعد."))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Lbl(tr("Choose .elf file", "اختيار ملف .elf")) }
                Button(enabled = !busy && hasElf, onClick = { sendServer() }) { Lbl(tr("Send to PS4", "إرسال إلى PS4")) }
            }
            if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        }

        Panel {
            SectionTitleHelp(tr("Copy for GoldHEN AutoRun", "نسخة لتشغيل AutoRun في GoldHEN"), tr("When on, every time you send the server a copy is also uploaded to /data/payloads on the PS4 through GoldHEN's FTP. This only places the file; whether GoldHEN's AutoRun then starts it reliably is not verified. You still choose it in GoldHEN's menu yourself.", "عند التفعيل، في كل مرة ترسل فيها السيرفر تُرفع نسخة منه أيضاً إلى /data/payloads على الـPS4 عبر FTP في GoldHEN. هذا يضع الملف فقط؛ أما هل يشغّله AutoRun في GoldHEN بشكل موثوق فلم يتم التحقق منه. وتختاره بنفسك من قائمة GoldHEN."))
            Dim(tr("Puts the server file in /data/payloads on the PS4 through GoldHEN's FTP server (turn FTP on in GoldHEN first; port 2121 in the GoldHEN documentation I found). The file name never changes, so later updates keep the same AutoRun entry. This does not start anything and does not touch the AutoRun list: adding it to AutoRun is done once in GoldHEN's menu (LaunchPad > Square > Add to AutoRun queue).",
                "يضع ملف السيرفر في /data/payloads على الـPS4 عبر خادم FTP الخاص بـGoldHEN (فعّل FTP في GoldHEN أولًا؛ المنفذ 2121 حسب وثائق GoldHEN التي وجدتها). اسم الملف ثابت، فتبقى نفس خانة AutoRun بعد التحديثات. هذا لا يشغّل شيئًا ولا يعدّل قائمة AutoRun: تُضاف مرة واحدة من قائمة GoldHEN (LaunchPad > Square > Add to AutoRun queue)."))
            Dim(tr("Not verified on a console: GoldHEN's FTP login rules (the app tries anonymous), and that AutoRun starts the server reliably. If the server ever freezes the PS4 at start-up, do not keep it in AutoRun.",
                "غير متحقَّق منه على جهاز: قواعد دخول FTP في GoldHEN (يجرّب التطبيق anonymous)، وأن AutoRun يشغّل السيرفر بثبات. إذا جمّد السيرفر الـPS4 عند الإقلاع فلا تُبقِه في AutoRun."))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(autoPlace, { autoPlace = it; AutoPlace.setEnabled(it) })
                Text(tr("Do this automatically every time the server is sent (default)", "افعل هذا تلقائيًا في كل مرة يُرسَل فيها السيرفر (الافتراضي)"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(ftpPort, { ftpPort = it.filter { c -> c.isDigit() }.take(5); AutoPlace.setPort(ftpPort) }, label = { Text(tr("FTP port", "منفذ FTP")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && !ftpBusy, onClick = { uploadAuto(false) }) { Lbl(tr("Put the built-in server in /data/payloads", "ضع السيرفر المضمّن في /data/payloads")) }
                if (hasElf) OutlinedButton(enabled = !busy && !ftpBusy, onClick = { uploadAuto(true) }) { Lbl(tr("Put the saved file instead", "ضع الملف المحفوظ بدلًا منه")) }
            }
            if (ftpBusy) LinearProgressIndicator(progress = { ftpProg / 100f }, modifier = Modifier.fillMaxWidth())
            if (ftpMsg.isNotEmpty()) Text(ftpMsg, fontWeight = FontWeight.SemiBold)
        }

        if (i != null && i.custom && !i.locked) Panel {
            SectionTitleHelp(tr("Download settings", "إعدادات التحميل"), tr("Controls how many downloads the server runs at the same time and related options. Fewer at once is gentler on the console and the network.", "يتحكم بعدد التحميلات التي يشغّلها السيرفر في نفس الوقت وبخيارات مرتبطة. عدد أقل يكون ألطف على الجهاز والشبكة."))
            Text(tr("Downloads at the same time", "التحميلات المتزامنة"))
            Chips(listOf(1, 2, 3, 4), i.maxParallel, "") { v -> push("{\"max_parallel\":$v}") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Choose connections automatically", "اختيار عدد الاتصالات تلقائيًا"))
                    Dim(tr("Adds connections while they really raise the speed", "يضيف اتصالات ما دامت ترفع السرعة فعلًا"))
                }
                Switch(checked = i.auto, onCheckedChange = { v -> push("{\"auto_conns\":$v}") })
            }
            if (i.auto) {
                Text(tr("Maximum connections per download", "أقصى عدد اتصالات للتحميل الواحد"))
                Chips(listOf(2, 4, 6, 8, 12), i.maxConns, "") { v -> push("{\"max_conns_per_download\":$v}") }
            } else {
                Text(tr("Connections per download", "الاتصالات لكل تحميل"))
                Chips(listOf(1, 2, 4, 6, 8), i.fixedConns, "") { v -> push("{\"fixed_conns\":$v}") }
            }
        }

        if (i != null && i.custom && !i.locked) Panel {
            SectionTitleHelp(tr("File access through the server", "الوصول إلى الملفات عبر الخادم"), tr("Lets this app list, read, rename and delete files in /data and /mnt through the server. Without it the Files tab, covers and similar features do not work. Deleting cannot be undone.", "يتيح لهذا التطبيق عرض وقراءة وإعادة تسمية وحذف الملفات في /data و /mnt عبر السيرفر. بدونه لا تعمل شاشة الملفات والأغلفة ونحوها. الحذف لا يمكن التراجع عنه."))
            Dim(tr("Lets the app list, read, rename and delete files in /data and /mnt through this server (port 6701), which keeps working while a game runs. Without it the Files tab, covers and file checks need PS4 server's web server, which the PS4 suspends during games. Anyone on your network who has the token (or any device if no token is set) could read those folders.",
                "يتيح للتطبيق عرض وقراءة وإعادة تسمية وحذف الملفات في /data و /mnt عبر هذا الخادم (المنفذ 6701) الذي يستمر بالعمل أثناء اللعب. بدونه تحتاج تبويبة الملفات والأغلفة وفحوص الملفات إلى خادم ويب PS4 server الذي يعلّقه الـPS4 أثناء الألعاب. أي جهاز في شبكتك يملك الرمز (أو أي جهاز إن لم يُضبط رمز) يستطيع قراءة هذه المجلدات."))
            if (i.fsApi < 1) Dim(tr("This server build has no file API. Send the newest server.", "نسخة الخادم هذه بلا واجهة ملفات. أرسل أحدث خادم."))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Allow", "السماح"), Modifier.weight(1f))
                Switch(checked = i.fsRoots.isNotEmpty(), enabled = i.fsApi >= 1 && !busy, onCheckedChange = { v ->
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { Ps4Server.setFsAccess(p, v) }
                        msg = if (e != null) Tx.t(e) else ""
                        refresh()
                    }
                })
            }
        }

        if (i != null && i.custom && !i.locked) Panel {
            SectionTitleHelp(tr("Server token", "رمز الخادم"), tr("A password for the server. If set, every request must carry it, so other devices on your Wi-Fi cannot control the server. This app sends the token saved for the PS4.", "كلمة سر للسيرفر. إن وُضعت فيجب أن يحملها كل طلب، فلا تستطيع أجهزة أخرى على شبكتك التحكم بالسيرفر. التطبيق يرسل الرمز المحفوظ لهذا الجهاز."))
            Dim(if (i.tokenSet) tr("A token is set. Every request must carry it; this app sends the one saved for this PS4.", "رمز مضبوط. يجب أن يحمله كل طلب؛ والتطبيق يرسل المحفوظ لهذا الجهاز.")
                else tr("No token is set: any device on your network can control the server. A token protects the /v2 routes (control, files, settings). PS4 server's own routes (add / list downloads) stay open so it keeps working. If you forget the token, resend the server from GoldHEN and clear dlserver_settings.json on the PS4.",
                    "لا رمز مضبوط: أي جهاز في شبكتك يستطيع التحكم بالخادم. الرمز يحمي مسارات /v2 (التحكم والملفات والإعدادات)، أما مسارات PS4 server نفسه (إضافة/عرض التحميلات) فتبقى مفتوحة ليستمر بالعمل. إن نسيت الرمز فأعد إرسال الخادم من GoldHEN وامسح dlserver_settings.json من الـPS4."))
            OutlinedTextField(tokenText, { tokenText = it.filter { c -> c.code in 33..126 } }, label = { Text(tr("New token (empty = remove)", "الرمز الجديد (فارغ = إزالة)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && (tokenText.isNotEmpty() || i.tokenSet), onClick = {
                val newTok = tokenText.trim()
                scope.launch {
                    busy = true
                    val c = withContext(Dispatchers.IO) { Ps4Server.setServerToken(p, newTok) }
                    if (c.ok) { val np = p.copy(token = newTok); Ps4Repo.save(np); Ps4Server.forget(np); info = withContext(Dispatchers.IO) { Ps4Server.info(np) }; checked = true; tokenText = ""; msg = if (newTok.isEmpty()) tr("Token removed.", "أُزيل الرمز.") else tr("Token set and saved in the app for this PS4.", "ضُبط الرمز وحُفظ في التطبيق لهذا الجهاز.") }
                    else msg = tr("Could not change the token: ", "تعذّر تغيير الرمز: ") + Tx.t(c.message)
                    busy = false
                }
            }) { Lbl(if (tokenText.isEmpty() && i.tokenSet) tr("Remove token", "إزالة الرمز") else tr("Set token", "ضبط الرمز")) }
        }

        Panel {
            SectionTitleHelp(tr("Server log", "سجل السيرفر"), tr("Shows what the server did, even when it is not running (by reading its file from the PS4). Useful if downloads keep failing.", "يعرض ما فعله السيرفر حتى لو كان متوقفاً (بقراءة ملفه من الـPS4). مفيد إن استمرت التحميلات في الفشل."))
            Dim(tr("Shows what the server did (also works when it is not running, by reading its file from the PS4). Send it to me if downloads keep failing.",
                "يعرض ما فعله السيرفر (ويعمل حتى لو كان متوقفًا بقراءة ملفه من الـPS4). أرسله لي إن استمرت التحميلات في الفشل."))
            OutlinedButton(enabled = !busy, onClick = { showLog() }) { Lbl(tr("Show log", "عرض السجل")) }
        }
    }

    val lt = logText
    if (lt != null) AlertDialog(onDismissRequest = { logText = null }, title = { Text(tr("Server log", "سجل السيرفر")) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(lt, style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { clip.setText(AnnotatedString(lt)); logText = null }) { Lbl(tr("Copy", "نسخ")) } },
        dismissButton = { TextButton(onClick = { logText = null }) { Lbl(tr("Close", "إغلاق")) } })
}
