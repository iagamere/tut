@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FULL = 3072     // longest side of the picture decoded in the viewer (a 1080p / 4K capture is shown at full detail)
private const val VTHUMB = 480    // video frame size

/** Returns the function that saves captures to the gallery album (asks for the storage permission first on Android 8/9 only). */
@Composable private fun rememberSaveAction(ps4: Ps4): (List<Capture>) -> Unit {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf<List<Capture>?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val w = waiting; waiting = null
        if (granted && w != null) CaptureSaver.enqueue(ps4, w)
        else if (!granted) CaptureSaver.say(tr("Saving to the gallery needs the storage permission.", "الحفظ في المعرض يحتاج إذن التخزين."))
    }
    return { list ->
        if (list.isNotEmpty()) {
            if (CaptureSaver.needsPermission(ctx)) { waiting = list; perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            else CaptureSaver.enqueue(ps4, list)
        }
    }
}

// ---------------------------------------------------------------- captures tab
@Composable internal fun CapturesTab(ps4: Ps4, reload: Int, onlyTitle: String? = null) {
    var scan by remember(ps4.id, reload) { mutableStateOf<CaptureScan?>(null) }
    var namesTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ps4.id, reload) {
        scan = null; scan = withContext(Dispatchers.IO) { Ps4Info.scanCaptures(ps4, force = reload != 0) }.let { sc -> if (onlyTitle == null) sc else CaptureScan(sc.items.filter { it.titleId == onlyTitle }, sc.notes, sc.truncated) }
        // game names (for the captions) come from the games list; read it quietly once if the Games tab has not done it yet
        if (Ps4Info.names.isEmpty()) { withContext(Dispatchers.IO) { runCatching { Ps4Info.scanGames(ps4) } }; namesTick++ }
    }
    var videos by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var confirmAll by remember { mutableStateOf<List<Capture>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Capture>?>(null) }
    var gone by remember(ps4.id, reload) { mutableStateOf(setOf<String>()) }          // deleted on the PS4 during this visit
    var delProg by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var delMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val save = rememberSaveAction(ps4)
    val saving by CaptureSaver.state.collectAsState()
    val busy by CaptureSaver.busy.collectAsState()
    val saved by CaptureSaver.saved.collectAsState()
    val msg by CaptureSaver.message.collectAsState()
    val s = scan
    val shown = remember(s, videos, gone) { s?.items?.filter { it.video == videos && it.path !in gone } ?: emptyList() }
    val ctx = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading the capture folders…", "جارٍ قراءة مجلدات اللقطات…")); return@Column }
        val left = s.items.filter { it.path !in gone }
        val nPhotos = left.count { !it.video }; val nVideos = left.size - nPhotos
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!videos, { videos = false; selected = emptySet() }, label = { Lbl(tr("Screenshots", "لقطات الشاشة") + " ($nPhotos)") })
            FilterChip(videos, { videos = true; selected = emptySet() }, label = { Lbl(tr("Videos", "الفيديوهات") + " ($nVideos)") })
        }
        val unsaved = shown.filter { !saved.containsKey(CaptureSaver.key(ps4, it)) }
        if (selected.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(shown.filter { it.path in selected }); selected = emptySet() }) {
                    Ico(R.drawable.ic_download, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Save selected", "حفظ المحدد") + " (${selected.size})")
                }
                OutlinedButton(onClick = { confirmDelete = shown.filter { it.path in selected } }, enabled = delProg == null) {
                    Ico(R.drawable.ic_delete, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Delete", "حذف") + " (${selected.size})")
                }
                TextButton(onClick = { selected = emptySet() }) { Lbl(tr("Cancel", "إلغاء")) }
            }
        } else if (unsaved.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (unsaved.size > 30) confirmAll = unsaved else save(unsaved) }) {
                    Ico(R.drawable.ic_download, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Save all to album", "حفظ الكل في الألبوم") + " (${unsaved.size})")
                }
            }
            Dim(tr("Tip: press and hold a picture to select several (to save or delete them).", "تلميح: اضغط مطولًا على صورة لتحديد عدة عناصر (لحفظها أو حذفها)."))
        }
        delProg?.let { (d, t) -> LinearProgressIndicator(progress = { d.toFloat() / t.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth()); Dim(tr("Deleting from the PS4 ", "جارٍ الحذف من الـPS4 ") + "${(d + 1).coerceAtMost(t)}/$t") }
        if (delProg == null && delMsg.isNotBlank()) Dim(delMsg)
        val st = saving
        if (st != null) {
            LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
            Dim(tr("Saving ", "جارٍ الحفظ ") + "${(st.done + 1).coerceAtMost(st.total)}/${st.total}: ${st.name}", maxLines = 1)
        } else msg?.let { Dim(it) }
        if (s.truncated) Dim(tr("Only the first files are shown (the folders are large).", "تظهر الملفات الأولى فقط (المجلدات كبيرة)."))
        if (shown.isEmpty()) {
            EmptyState(R.drawable.ic_folder, if (videos) tr("No videos found.", "لا توجد فيديوهات.") else tr("No screenshots found.", "لا توجد لقطات شاشة."))
            NoteList(s.notes + (if (s.items.isEmpty()) listOf(tr("Open the Device tab and use \"Check paths\" to see which folders the console answers.", "افتح تبويب الجهاز واستعمل «فحص المسارات» لترى أي المجلدات يردّ عليها الجهاز.")) else emptyList()))
        } else LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.path }) { c ->
                val k = CaptureSaver.key(ps4, c)
                CaptureCell(ps4, c, c.path in selected, saved.containsKey(k), k in busy, namesTick,
                    onClick = {
                        if (selected.isNotEmpty()) { selected = if (c.path in selected) selected - c.path else selected + c.path }
                        else { viewing = shown.indexOf(c) }
                    },
                    onLong = { selected = if (c.path in selected) selected - c.path else selected + c.path })
            }
        }
    }
    confirmAll?.let { l ->
        AlertDialog(onDismissRequest = { confirmAll = null },
            title = { Text(tr("Save ${l.size} items?", "حفظ ${l.size} عنصرًا؟")) },
            text = { Text(tr("They will be copied from the PS4 into the “${CaptureSaver.ALBUM}” album in your gallery, one after another. Keep the app open until it finishes.",
                "ستُنسخ من الـPS4 إلى ألبوم «${CaptureSaver.ALBUM}» في المعرض واحدًا تلو الآخر. أبقِ التطبيق مفتوحًا حتى ينتهي.")) },
            confirmButton = { TextButton(onClick = { save(l); confirmAll = null }) { Lbl(tr("Save", "حفظ")) } },
            dismissButton = { TextButton(onClick = { confirmAll = null }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
    confirmDelete?.let { l ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete ${l.size} from the PS4?", "حذف ${l.size} من الـPS4؟")) },
            text = { Text(tr("They are deleted from the console itself and cannot be recovered. Copies already saved to your gallery are not touched.",
                "تُحذف من الجهاز نفسه ولا يمكن استرجاعها. النسخ المحفوظة في معرض هاتفك لا تتأثر.")) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null; selected = emptySet(); viewing = null
                scope.launch {
                    delMsg = ""; delProg = 0 to l.size
                    val err = withContext(Dispatchers.IO) { Ps4Server.allowCaptureDelete(ps4) }
                    if (err != null) { delProg = null; delMsg = tr("Could not delete: ", "تعذّر الحذف: ") + err }
                    else {
                        var ok = 0; var firstErr = ""
                        l.forEachIndexed { i, c ->
                            delProg = i to l.size
                            val r = withContext(Dispatchers.IO) { Ps4Server.deleteCapture(ps4, c.path) }
                            if (r.ok || r.message.startsWith("No such")) { ok++; gone = gone + c.path } else if (firstErr.isEmpty()) firstErr = r.message
                        }
                        delProg = null
                        Ps4Info.invalidate()
                        delMsg = if (firstErr.isEmpty()) tr("Deleted $ok from the PS4.", "حُذف $ok من الـPS4.")
                                 else tr("Deleted $ok of ${l.size}. The PS4 refused the rest: ", "حُذف $ok من ${l.size}. رفض الـPS4 الباقي: ") + firstErr
                    }
                }
            }) { Lbl(tr("Delete", "حذف")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
    val vi = viewing
    if (vi != null && vi in shown.indices) {
        CaptureViewer(ps4, shown, vi, saved, busy, saving, onSave = { save(listOf(it)) },
            onOpen = { CaptureSaver.open(ctx, ps4, it) }, onDelete = { confirmDelete = listOf(it) }, onClose = { viewing = null })
    }
}

@Composable private fun CaptureCell(ps4: Ps4, c: Capture, selected: Boolean, saved: Boolean, busy: Boolean, nameTick: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val title = remember(nameTick, c.titleId) { Ps4Info.names[c.titleId] ?: c.titleId.ifEmpty { c.name } }
    val bmp by produceState<Bitmap?>(if (c.video) Thumbs.peekVideo(ps4, c.path, VTHUMB) else Thumbs.peek(ps4, c.path, 320), c.path) {
        if (value == null) value = if (c.video) Thumbs.videoFrame(ps4, c.path, c.size, VTHUMB) else Thumbs.load(ps4, c.path, c.size, 320)
    }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Ico(if (c.video) R.drawable.ic_play else R.drawable.ic_file, 30.dp, MaterialTheme.colorScheme.outline)
            if (c.video && b != null) Ico(R.drawable.ic_play, 32.dp, Color.White, Modifier.align(Alignment.Center))
            if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)))
            if (selected) Ico(R.drawable.ic_check_circle, 24.dp, Color.White, Modifier.align(Alignment.TopStart).padding(4.dp))
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), strokeWidth = 2.dp, color = Color.White)
            else if (saved) Ico(R.drawable.ic_check, 20.dp, Color.White, Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(2.dp))
        }
        Column(Modifier.padding(6.dp)) {
            Dim(title, maxLines = 1)
            Dim(Fmt.bytes(c.size) + if (c.mtime > 0) " • " + Fmt.dt(c.mtime).substringBefore(' ') else "", maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- full-screen viewer
@Composable private fun CaptureViewer(ps4: Ps4, items: List<Capture>, start: Int, saved: Map<String, String>, busy: Set<String>, saving: CaptureSaver.State?,
                                      onSave: (Capture) -> Unit, onOpen: (Capture) -> Unit, onDelete: (Capture) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].path }) { i ->
                val c = items[i]
                if (c.video) VideoPage(ps4, c, saved.containsKey(CaptureSaver.key(ps4, c)), onTap = { chrome = !chrome }, onOpen = { onOpen(c) })
                else PhotoPage(ps4, c, onTap = { chrome = !chrome })
            }
            val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            val k = CaptureSaver.key(ps4, cur)
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Ico(R.drawable.ic_close, 24.dp, Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(Ps4Info.names[cur.titleId] ?: cur.titleId.ifEmpty { cur.name }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDelete(cur) }) { Ico(R.drawable.ic_delete, 24.dp, Color.White) }
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))).navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Fmt.bytes(cur.size) + "  •  " + Fmt.dt(cur.mtime) + "  •  " + cur.name, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        k in busy -> {
                            if (saving != null && saving.key == k) LinearProgressIndicator(progress = { saving.fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(tr("Saving to the album…", "جارٍ الحفظ في الألبوم…"), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                        saved.containsKey(k) -> FilledTonalButton(onClick = { onOpen(cur) }, modifier = Modifier.fillMaxWidth()) {
                            Ico(R.drawable.ic_check_circle, 20.dp); Spacer(Modifier.width(8.dp))
                            Lbl(tr("Saved in “${CaptureSaver.ALBUM}” — open", "محفوظ في «${CaptureSaver.ALBUM}» — فتح"))
                        }
                        else -> Button(onClick = { onSave(cur) }, modifier = Modifier.fillMaxWidth()) {
                            Ico(R.drawable.ic_download, 20.dp); Spacer(Modifier.width(8.dp)); Lbl(tr("Save to album", "حفظ في الألبوم"))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PhotoPage(ps4: Ps4, c: Capture, onTap: () -> Unit) {
    var failed by remember(c.path) { mutableStateOf(false) }
    val low = remember(c.path) { Thumbs.peek(ps4, c.path, 320) }       // already decoded for the grid: shown at once while the full picture loads
    val full by produceState<Bitmap?>(Thumbs.peek(ps4, c.path, FULL), c.path) {
        if (value == null) { value = Thumbs.load(ps4, c.path, c.size, FULL); if (value == null) failed = true }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = full ?: low
        if (shown != null) ZoomImage(shown, onTap)
        else Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        if (full == null && !failed) CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
        if (full == null && failed) Text(tr("Could not load this picture.", "تعذّر تحميل هذه الصورة.") + "\n" + Thumbs.lastError,
            color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

@Composable private fun VideoPage(ps4: Ps4, c: Capture, saved: Boolean, onTap: () -> Unit, onOpen: () -> Unit) {
    var stream by remember(c.path) { mutableStateOf(false) }
    val frame by produceState<Bitmap?>(Thumbs.peekVideo(ps4, c.path, VTHUMB), c.path) { if (value == null) value = Thumbs.videoFrame(ps4, c.path, c.size, VTHUMB) }
    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            if (stream) StreamPlayer(ps4, c, Modifier.fillMaxSize())
            else {
                frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                FilledIconButton(onClick = { stream = true }, modifier = Modifier.size(64.dp)) { Ico(R.drawable.ic_play, 36.dp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("Plays straight from the PS4: nothing is downloaded. Saving to the album is optional.", "يعمل مباشرة من الـPS4: لا يُحمَّل شيء. الحفظ في الألبوم اختياري."),
            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        if (saved) TextButton(onClick = onOpen) { Lbl(tr("Open the saved copy in the gallery", "فتح النسخة المحفوظة في المعرض")) }
    }
}

/** Pinch to zoom (up to 6x), drag to pan while zoomed, double-tap to zoom in/out, single tap to hide the bars. At 1x horizontal swipes pass to the pager. */
@Composable private fun ZoomImage(bmp: Bitmap, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    val multi = ev.changes.count { it.pressed } > 1
                    if (multi || scale > 1f) {
                        val z = ev.calculateZoom(); val p = ev.calculatePan()
                        val ns = (scale * z).coerceIn(1f, 6f)
                        val mx = box.width * (ns - 1f) / 2f; val my = box.height * (ns - 1f) / 2f
                        offset = if (ns <= 1f) Offset.Zero else Offset((offset.x + p.x).coerceIn(-mx, mx), (offset.y + p.y).coerceIn(-my, my))
                        scale = ns
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), null,
            Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit)
    }
}
