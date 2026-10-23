@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun Ps4InfoScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ps4?.id, reload) {
        val p = ps4 ?: return@LaunchedEffect
        checking = true
        reason = withContext(Dispatchers.IO) { Ps4Server.prepareInfo(p) }
        checking = false
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackHeader(tr("PS4 information", "معلومات الـPS4"), nav) { IconButton(onClick = { reload++ }) { Ico(R.drawable.ic_refresh) } }
        if (ps4 == null) EmptyState(R.drawable.ic_console, tr("Add a PS4 first.", "أضف جهاز PS4 أولًا."))
        else if (checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Contacting ${ps4.name}…", "جارٍ الاتصال بـ ${ps4.name}…")) }
        else if (reason != null) Panel {
            Text(Tx.t(reason!!))
            Button(onClick = { reload++ }) { Ico(R.drawable.ic_refresh, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Try again", "إعادة المحاولة")) }
        } else {
            val tabs = listOf(tr("Captures", "اللقطات"), tr("Storage", "التخزين"), tr("Games", "الألعاب"), tr("Device", "الجهاز"))
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Lbl(t) }) } }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> CapturesTab(ps4, reload)
                    1 -> StorageTab(ps4, reload)
                    2 -> GamesTab(ps4, reload)
                    else -> DeviceTab(ps4, reload)
                }
            }
        }
    }
}

@Composable internal fun NoteList(notes: List<String>) {
    if (notes.isEmpty()) return
    Panel { Dim(tr("Details from the console (technical):", "تفاصيل من الجهاز (تقنية):")); notes.take(8).forEach { Dim(it) } }
}

// ---------------------------------------------------------------- storage
@Composable private fun StorageTab(ps4: Ps4, reload: Int) {
    var res by remember(ps4.id, reload) { mutableStateOf<Pair<List<Volume>, List<String>>?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var delOn by remember { mutableStateOf(Ps4Server.deleteAllowed()) }
    LaunchedEffect(ps4.id, reload) { res = null; res = withContext(Dispatchers.IO) { Ps4Info.storage(ps4) } }
    if (browsing) { StorageBrowser(ps4, delOn) { browsing = false }; return }
    val r = res
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (r == null) { item { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
        else {
            if (r.first.isEmpty()) item { EmptyState(R.drawable.ic_folder, tr("The console did not report any storage.", "لم يُبلغ الجهاز عن أي تخزين.")) }
            items(r.first, key = { it.path }) { v ->
                val used = (v.total - v.free).coerceAtLeast(0)
                Panel {
                    Text(v.label, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { (used.toDouble() / v.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(tr("Free", "المتبقي") + ": " + Fmt.bytes(v.free) + "   •   " + tr("Used", "المستخدم") + ": " + Fmt.bytes(used))
                    Dim(tr("Total", "الإجمالي") + ": " + Fmt.bytes(v.total) + "  •  " + v.path)
                }
            }
        }
        item {
            Panel {
                Text(tr("What is on the console", "ما الموجود على الجهاز"), fontWeight = FontWeight.SemiBold)
                Dim(tr("Browse the folders the server can read: see every file and folder with its size, and which ones are protected.", "تصفّح المجلدات التي يقرؤها الخادم: كل ملف ومجلد مع حجمه، وأيها محمي."))
                Button(onClick = { browsing = true }) { Ico(R.drawable.ic_folder, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Browse files", "تصفّح الملفات")) }
            }
        }
        item { DeleteSettingsPanel(delOn) { delOn = it; Ps4Server.setDeleteAllowed(it) } }
        if (r != null) item { NoteList(r.second) }
    }
}

// ---------------------------------------------------------------- device + diagnostics
@Composable private fun DeviceTab(ps4: Ps4, reload: Int) {
    val scope = rememberCoroutineScope()
    var sys by remember(ps4.id, reload) { mutableStateOf<Pair<List<Pair<String, String>>, List<String>>?>(null) }
    var checks by remember(ps4.id, reload) { mutableStateOf<List<PathCheck>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var extra by remember(ps4.id) { mutableStateOf(Ps4Server.roExtra().joinToString("\n")) }
    var msg by remember { mutableStateOf("") }
    var trop by remember { mutableStateOf<List<String>?>(null) }
    var tropBusy by remember { mutableStateOf(false) }
    var snapBusy by remember { mutableStateOf(false) }
    var snapLines by remember { mutableStateOf<List<String>?>(null) }
    var snapSavedAt by remember { mutableStateOf(TrophySnap.saved(ps4)?.at ?: 0L) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var info by remember(ps4.id, reload) { mutableStateOf<Ps4Server.ServerInfo?>(null) }
    LaunchedEffect(ps4.id, reload) { sys = null; val (i, s) = withContext(Dispatchers.IO) { Ps4Server.info(ps4) to Ps4Info.deviceRows(Ps4Server.sysinfo(ps4)) }; info = i; sys = s }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text(ps4.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Dim(ps4.host)
                info?.let { Dim(tr("PS4 server", "خادم PS4") + ": " + it.version) }
                val st = sys
                if (st == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    st.first.forEach { (k, v) -> Row { Text(k, Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(0.55f)) } }
                    if (st.first.isEmpty()) Dim(tr("The console did not report system details.", "لم يُبلغ الجهاز عن تفاصيل النظام."))
                    if (st.second.isNotEmpty()) Dim(tr("Not reported by this console: ", "لم يُبلَّغ عنها من هذا الجهاز: ") + st.second.joinToString())
                }
            }
        }
        item {
            Panel {
                Text(tr("Folders the app may read", "المجلدات التي يقرؤها التطبيق"), fontWeight = FontWeight.SemiBold)
                Dim(tr("Read-only: the server never changes these. Add more folders below (one per line), e.g. where your captures really are.", "للقراءة فقط: لا يغيّرها الخادم أبدًا. أضف مجلدات أخرى أدناه (واحد في كل سطر)، مثل المكان الفعلي للقطاتك."))
                OutlinedTextField(extra, { extra = it }, label = { Text(tr("Extra folders", "مجلدات إضافية")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { val e = withContext(Dispatchers.IO) { Ps4Server.setRoExtra(ps4, extra) }; msg = if (e == null) tr("Saved and sent to the server.", "حُفظ وأُرسل إلى الخادم.") else Tx.t(e) } }) { Lbl(tr("Save", "حفظ")) }
                    FilledTonalButton(enabled = !checking, onClick = { scope.launch { checking = true; checks = null; checks = withContext(Dispatchers.IO) { Ps4Info.checkPaths(ps4) }; checking = false } }) { Lbl(tr("Check paths", "فحص المسارات")) }
                }
                if (msg.isNotBlank()) Dim(msg)
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            Panel {
                Text(tr("Trophies and themes (discovery)", "الإنجازات والسمات (فحص أولي)"), fontWeight = FontWeight.SemiBold)
                Dim(tr("Reads the account folders and shows what the console stores about trophies. Send the result so the trophy screen can be built on the real layout.", "يقرأ مجلدات الحسابات ويعرض ما يخزّنه الجهاز عن الإنجازات. أرسل النتيجة ليُبنى شاشة الإنجازات على البنية الفعلية."))
                FilledTonalButton(enabled = !tropBusy, onClick = { scope.launch { tropBusy = true; trop = null; trop = withContext(Dispatchers.IO) { Ps4Trophy.probe(ps4, ctx.cacheDir) }; tropBusy = false } }) { Lbl(tr("Scan trophies and themes", "فحص الإنجازات والسمات")) }
                if (tropBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                trop?.let { l ->
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(l.joinToString("\n"))) }) { Lbl(tr("Copy result", "نسخ النتيجة")) }
                    l.forEach { Dim(it) }
                }
            }
        }
        item {
            Panel {
                Text(tr("Trophy files: before / after", "ملفات الإنجازات: قبل / بعد"), fontWeight = FontWeight.SemiBold)
                Dim(tr("1) Press Snapshot. 2) Earn one easy trophy on the PS4 (offline if you can). 3) Press Compare and send the result. Read-only: it only lists file names, sizes and times.", "١) اضغط «لقطة». ٢) حقّق إنجازًا سهلًا على الجهاز (بدون إنترنت إن أمكن). ٣) اضغط «قارن» وأرسل النتيجة. للقراءة فقط: يقرأ أسماء الملفات وأحجامها وأوقاتها فقط."))
                if (snapSavedAt > 0) Dim(tr("Saved snapshot: ", "اللقطة المحفوظة: ") + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(snapSavedAt)))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !snapBusy, onClick = { scope.launch {
                        snapBusy = true; snapLines = null
                        val s = withContext(Dispatchers.IO) { runCatching { TrophySnap.take(ps4) } }
                        snapLines = s.getOrNull()?.let { listOf(tr("Snapshot saved: ${it.items.size} entries.", "حُفظت اللقطة: ${it.items.size} عنصرًا.")) + it.notes } ?: listOf("Error: ${s.exceptionOrNull()?.message}")
                        snapSavedAt = TrophySnap.saved(ps4)?.at ?: 0L; snapBusy = false } }) { Lbl(tr("Snapshot", "لقطة")) }
                    Button(enabled = !snapBusy && snapSavedAt > 0, onClick = { scope.launch {
                        snapBusy = true; snapLines = null
                        val r = withContext(Dispatchers.IO) { runCatching {
                            val old = TrophySnap.saved(ps4) ?: throw IllegalStateException("No saved snapshot.")
                            val now = TrophySnap.take(ps4)
                            TrophySnap.diff(old, now) + now.notes } }
                        snapLines = r.getOrElse { listOf("Error: ${it.message}") }
                        snapSavedAt = TrophySnap.saved(ps4)?.at ?: 0L; snapBusy = false } }) { Lbl(tr("Compare", "قارن")) }
                }
                if (snapBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                snapLines?.let { l ->
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(l.joinToString("\n"))) }) { Lbl(tr("Copy result", "نسخ النتيجة")) }
                    l.take(120).forEach { Dim(it) }
                    if (l.size > 120) Dim(tr("… ${l.size - 120} more lines (use Copy result).", "… ${l.size - 120} أسطر أخرى (استخدم نسخ النتيجة)."))
                }
            }
        }
        checks?.let { l ->
            items(l, key = { it.path }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(if (c.ok) R.drawable.ic_check_circle else R.drawable.ic_error, 20.dp, if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Column { Text(c.path, style = MaterialTheme.typography.bodyMedium); Dim(c.detail) }
                }
            }
        }
    }
}
