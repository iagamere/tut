@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why an item is protected, in the app language. */
internal fun whyText(w: DeletePolicy.Why): String = when (w) {
    DeletePolicy.Why.OK -> ""
    DeletePolicy.Why.BAD_PATH -> tr("Invalid path.", "مسار غير صالح.")
    DeletePolicy.Why.TOP_LEVEL -> tr("Top-level folders are never deleted.", "المجلدات الرئيسية لا تُحذف أبدًا.")
    DeletePolicy.Why.ZONE_ROOT -> tr("This folder itself stays; you can delete what is inside it.", "هذا المجلد نفسه يبقى؛ يمكنك حذف ما بداخله.")
    DeletePolicy.Why.SYSTEM -> tr("System / console data: protected.", "بيانات النظام / الجهاز: محمية.")
    DeletePolicy.Why.GAME_FILES -> tr("Installed game files: delete the game from the Games tab (double confirmation).", "ملفات لعبة مثبتة: احذف اللعبة من تبويب الألعاب (بتأكيد مزدوج).")
    DeletePolicy.Why.SAVES -> tr("Saved games: protected.", "الحفظ (Save): محمي.")
    DeletePolicy.Why.TROPHIES -> tr("Trophy data: protected.", "بيانات الإنجازات: محمية.")
    DeletePolicy.Why.ACCOUNTS -> tr("Accounts and profiles: protected.", "الحسابات والملفات الشخصية: محمية.")
    DeletePolicy.Why.LOADER -> tr("GoldHEN / payloads / PS4 server files: protected.", "ملفات GoldHEN والـpayloads وPS4 server: محمية.")
    DeletePolicy.Why.SYSTEM_FILE -> tr("Game or system file (eboot, sce_sys, modules, saves): protected.", "ملف لعبة أو نظام (eboot وsce_sys والوحدات والحفظ): محمي.")
    DeletePolicy.Why.OUTSIDE_ZONES -> tr("Outside the places this app may delete from (download folder, captures, USB drives).", "خارج الأماكن التي يسمح التطبيق بالحذف منها (مجلد التحميل واللقطات ومحركات USB).")
    DeletePolicy.Why.SWITCHED_OFF -> tr("Deleting is switched off: turn it on above (Storage > Deleting).", "الحذف معطّل: فعّله بالأعلى (التخزين ← الحذف).")
}

private fun placeLabel(p: String): String? = when (p) {
    "/data" -> tr("Data", "البيانات")
    "/mnt" -> tr("Drives (USB / extended storage)", "المحركات (USB / التخزين الموسّع)")
    "/user/app" -> tr("Installed games", "الألعاب المثبتة")
    "/user/appmeta" -> tr("Game pictures and info", "صور ومعلومات الألعاب")
    "/user/av_contents" -> tr("Captures (screenshots and videos)", "اللقطات (صور وفيديو)")
    "/user/home" -> tr("Accounts", "الحسابات")
    "/user/trophy" -> tr("Trophies", "الإنجازات")
    else -> null
}
private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

/** The Storage tab's file browser: lists any folder the server may read, tells for every item whether this app would ever delete it, and deletes the allowed ones. */
@Composable internal fun StorageBrowser(ps4: Ps4, delOn: Boolean, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var places by remember(ps4.id) { mutableStateOf<List<String>?>(null) }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val measures = remember { mutableStateMapOf<String, Measure>() }
    var measuring by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, FsEntry>?>(null) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(ps4.id, reload) {
        places = withContext(Dispatchers.IO) {
            runCatching { Ps4Server.isV2(ps4, 4000) }
            (Ps4Server.fsRootsKnown(ps4) + Ps4Server.roRoots(ps4)).distinct().sorted()
        }
    }
    LaunchedEffect(path, reload) {
        val p = path ?: return@LaunchedEffect
        entries = null; error = null
        try { entries = withContext(Dispatchers.IO) { Ps4Server.fsList(ps4, p, 20_000) }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) }
        catch (e: Ps4Error) { error = e.message ?: "error" }
        catch (e: Exception) { error = Ps4Api.friendly(e) }
    }
    fun goUp() {
        val p = path
        if (p == null) { onExit(); return }
        msg = ""
        path = if (places.orEmpty().contains(p)) null else p.substringBeforeLast('/').ifEmpty { null }
    }
    BackHandler(enabled = !deleting) { goUp() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { goUp() }, enabled = !deleting) { Ico(R.drawable.ic_arrow_back) }
            Column(Modifier.weight(1f)) {
                Text(tr("File browser", "متصفح الملفات"), fontWeight = FontWeight.SemiBold)
                Dim(path ?: tr("Places the server may read", "الأماكن التي يقرؤها الخادم"), maxLines = 1)
            }
            IconButton(onClick = { reload++ }, enabled = !deleting) { Ico(R.drawable.ic_refresh) }
        }
        if (deleting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Deleting… keep the app open.", "جارٍ الحذف… أبقِ التطبيق مفتوحًا.")) }
        else if (msg.isNotBlank()) Panel { Text(msg) }

        val p = path
        if (p == null) {
            val pl = places
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (pl == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else {
                    val dl = ps4.dest.trimEnd('/')
                    if (dl.startsWith("/") && DeletePolicy.zones(ps4.dest).contains(dl)) item("dl") {
                        PlaceRow(tr("Download folder", "مجلد التحميل"), dl, R.drawable.ic_download) { path = dl }
                    }
                    if (pl.isEmpty()) item { EmptyState(R.drawable.ic_folder, tr("The server did not report any readable folder.", "لم يُبلغ الخادم عن أي مجلد قابل للقراءة.")) }
                    items(pl, key = { it }) { r -> PlaceRow(placeLabel(r) ?: r, r, R.drawable.ic_folder) { path = r } }
                    item { Dim(tr("Locked items can be looked at but are never deleted by this app.", "العناصر المقفلة يمكن عرضها لكن التطبيق لا يحذفها أبدًا.")) }
                }
            }
        } else {
            val l = entries
            if (error != null) Panel { Text(Tx.t(error!!)); Button(onClick = { reload++ }) { Lbl(tr("Try again", "إعادة المحاولة")) } }
            else if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val here = measures[p]
                Dim(tr("${l.count { it.isDir }} folders, ${l.count { !it.isDir }} files", "${l.count { it.isDir }} مجلد، ${l.count { !it.isDir }} ملف") +
                    "  •  " + tr("files here: ", "ملفات هنا: ") + Fmt.bytes(l.filter { !it.isDir }.sumOf { it.size.coerceAtLeast(0) }))
                if (l.any { it.isDir } || here != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = measuring == null, onClick = {
                        measuring = p
                        scope.launch(Dispatchers.IO) { val m = StorageOps.measure(ps4, p, cancelled = { !isActive }); withContext(Dispatchers.Main) { measures[p] = m; measuring = null } }
                    }) { Lbl(tr("Measure this folder", "قياس هذا المجلد")) }
                    if (measuring == p) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    here?.let { Dim((if (it.complete) "" else "≥ ") + Fmt.bytes(it.bytes) + " • " + tr("${it.files} files", "${it.files} ملف")) }
                }
                if (l.isEmpty()) EmptyState(R.drawable.ic_folder, tr("Empty folder.", "مجلد فارغ."))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(l, key = { it.name }) { e ->
                        val full = join(p, e.name)
                        val v = DeletePolicy.check(full, ps4.dest, delOn)
                        val m = measures[full]
                        Row(Modifier.fillMaxWidth().clickable(enabled = e.isDir && !deleting) { path = full; msg = "" }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Ico(if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file, 24.dp, MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Dim(buildString {
                                    if (e.isDir) append(m?.let { (if (it.complete) "" else "≥ ") + Fmt.bytes(it.bytes) } ?: tr("folder", "مجلد")) else append(Fmt.bytes(e.size))
                                    if (e.mtime > 0) append("  •  ").append(Fmt.dt(e.mtime))
                                })
                            }
                            if (e.isDir && m == null) TextButton(enabled = measuring == null && !deleting, onClick = {
                                measuring = full
                                scope.launch(Dispatchers.IO) { val r = StorageOps.measure(ps4, full, cancelled = { !isActive }); withContext(Dispatchers.Main) { measures[full] = r; measuring = null } }
                            }) { if (measuring == full) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Lbl(tr("Size", "الحجم")) }
                            if (v.ok) IconButton(enabled = !deleting, onClick = { confirm = full to e }) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.error) }
                            else IconButton(onClick = { msg = e.name + ": " + whyText(v.why) }) { Ico(R.drawable.ic_lock, 20.dp, MaterialTheme.colorScheme.outline) }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (full, e) ->
        val m = measures[full]
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(tr("Delete “${e.name}”?", "حذف «${e.name}»؟")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Dim(full)
                Text(if (e.isDir) tr("The folder and everything inside it will be deleted from the PS4 itself. This cannot be undone.", "سيُحذف المجلد وكل ما بداخله من الـPS4 نفسه. لا يمكن التراجع.")
                    else tr("The file will be deleted from the PS4 itself. This cannot be undone.", "سيُحذف الملف من الـPS4 نفسه. لا يمكن التراجع."))
                if (e.isDir && m != null) Dim(tr("Size: ", "الحجم: ") + (if (m.complete) "" else "≥ ") + Fmt.bytes(m.bytes) + " • " + tr("${m.files} files", "${m.files} ملف"))
                if (!e.isDir) Dim(tr("Size: ", "الحجم: ") + Fmt.bytes(e.size))
            } },
            confirmButton = { TextButton(onClick = {
                confirm = null; deleting = true; msg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) { StorageOps.delete(ps4, full) }
                    deleting = false; msg = (if (r.ok) e.name + ": " else "") + r.message
                    if (r.ok) { measures.remove(full); path?.let { measures.remove(it) } }
                    reload++
                }
            }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
}

@Composable private fun PlaceRow(title: String, sub: String, icon: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ico(icon, 24.dp, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub != title) Dim(sub, maxLines = 1) }
            Ico(R.drawable.ic_arrow_forward, 18.dp, MaterialTheme.colorScheme.outline)
        }
    }
}

/** The "Deleting" settings panel of the Storage tab: the master switch and what is, and is never, deleted. */
@Composable internal fun DeleteSettingsPanel(on: Boolean, onChange: (Boolean) -> Unit) {
    Panel {
        Text(tr("Deleting", "الحذف"), fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Allow deleting from this app", "السماح بالحذف من هذا التطبيق"), Modifier.weight(1f)); Switch(checked = on, onCheckedChange = onChange)
        }
        Dim(tr("Off by default. When on, you can delete only: files in the download folder, captures, files on USB drives, and installed games (Games tab, two confirmations).",
            "معطّل افتراضيًا. عند تفعيله يمكنك حذف: ملفات مجلد التحميل، واللقطات، وملفات محركات USB، والألعاب المثبتة (تبويب الألعاب، بتأكيدين) فقط."))
        Dim(tr("Never deleted, whatever you tap: system files, accounts, saved games, trophies, GoldHEN / payloads / PS4 server files, game executables and modules, and top-level folders. Saves and trophies also survive deleting a game.",
            "لا يُحذف أبدًا مهما ضغطت: ملفات النظام والحسابات والحفظ (Save) والإنجازات وملفات GoldHEN والـpayloads وPS4 server وملفات تشغيل الألعاب والوحدات والمجلدات الرئيسية. والحفظ والإنجازات تبقى حتى بعد حذف لعبة."))
    }
}
