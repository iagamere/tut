@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Feeds MediaPlayer straight from the PS4 through /v2/fs/read, so a video plays WITHOUT being downloaded (nothing is written on the phone).
 * 1 MiB blocks, the last 16 are kept in memory (seeking back is instant) and the block after the one being read is fetched ahead by a second thread.
 */
class PsStreamSource(private val ps4: Ps4, private val path: String, private val total: Long) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 16
    }
    private val inflight = HashSet<Long>()
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile var lastError: String = ""
    @Volatile private var closed = false

    override fun getSize(): Long = total

    private fun fetch(idx: Long): ByteArray {
        val start = idx * BLOCK
        return Ps4Server.fsRead(ps4, path, start, minOf(BLOCK.toLong(), total - start).toInt(), 20_000)
    }

    private fun prefetch(idx: Long) {
        if (closed || idx * BLOCK >= total) return
        synchronized(this) { if (blocks.containsKey(idx) || !inflight.add(idx)) return }
        try {
            pool.execute {
                try { val d = fetch(idx); if (d.isNotEmpty() && !closed) synchronized(this) { blocks[idx] = d } }
                catch (e: Exception) { /* the real read will try again and report */ }
                finally { synchronized(this) { inflight.remove(idx) } }
            }
        } catch (e: Exception) { synchronized(this) { inflight.remove(idx) } }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed || position < 0 || position >= total) return -1
        val want = minOf(size.toLong(), total - position).toInt()
        var done = 0
        while (done < want) {
            val pos = position + done; val idx = pos / BLOCK
            var blk: ByteArray? = synchronized(this) { blocks[idx] }
            if (blk == null) {
                blk = try { fetch(idx) } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; throw IOException(lastError) }
                if (blk.isEmpty()) { lastError = "The PS4 returned no data"; throw IOException(lastError) }
                synchronized(this) { blocks[idx] = blk }
            }
            prefetch(idx + 1)
            val inBlk = (pos - idx * BLOCK).toInt()
            if (inBlk >= blk.size) break
            val n = minOf(want - done, blk.size - inBlk)
            System.arraycopy(blk, inBlk, buffer, offset + done, n); done += n
        }
        return if (done == 0) -1 else done
    }

    override fun close() { closed = true; pool.shutdownNow(); synchronized(this) { blocks.clear() } }

    companion object { const val BLOCK = 1 shl 20 }
}

private fun mmss(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Player with play/pause and a seek bar. Starts by itself as soon as it is composed; the caller composes it only after the user taps play. */
@Composable fun StreamPlayer(ps4: Ps4, c: Capture, modifier: Modifier = Modifier) {
    val mp = remember(c.path) { MediaPlayer() }
    var src by remember(c.path) { mutableStateOf<PsStreamSource?>(null) }
    var surface by remember(c.path) { mutableStateOf<Surface?>(null) }
    var prepared by remember(c.path) { mutableStateOf(false) }
    var ready by remember(c.path) { mutableStateOf(false) }
    var playing by remember(c.path) { mutableStateOf(false) }
    var pos by remember(c.path) { mutableIntStateOf(0) }
    var dur by remember(c.path) { mutableIntStateOf(0) }
    var dragging by remember(c.path) { mutableStateOf<Float?>(null) }
    var err by remember(c.path) { mutableStateOf<String?>(null) }
    var ratio by remember(c.path) { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(c.path) {
        onDispose {
            runCatching { mp.setSurface(null) }; runCatching { mp.release() }
            runCatching { src?.close() }; runCatching { surface?.release() }
        }
    }
    // 1) the size of the file (MediaPlayer needs it up front)
    LaunchedEffect(c.path) {
        try {
            val size = if (c.size > 0) c.size else withContext(Dispatchers.IO) { Ps4Server.fsSize(ps4, c.path, 6000) }
            if (size <= 0) err = tr("The file is empty or could not be read.", "الملف فارغ أو تعذّرت قراءته.") else src = PsStreamSource(ps4, c.path, size)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { err = tr("Cannot reach the PS4: ", "تعذّر الوصول إلى الـPS4: ") + (e.message ?: e.javaClass.simpleName) }
    }
    // 2) when both the source and the picture surface exist: connect them and start
    LaunchedEffect(src, surface) {
        val s = src; val sf = surface
        if (s == null || sf == null) return@LaunchedEffect
        try {
            mp.setSurface(sf)
            if (prepared) return@LaunchedEffect          // the surface was only re-created (rotation, etc.)
            prepared = true
            mp.setOnPreparedListener { ready = true; dur = it.duration; it.start(); playing = true }
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
            mp.setOnCompletionListener { playing = false; pos = dur }
            mp.setOnErrorListener { _, what, extra ->
                err = tr("Cannot play this video", "تعذّر تشغيل هذا الفيديو") + " ($what/$extra)" + (s.lastError.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "")
                true
            }
            mp.setDataSource(s)
            mp.prepareAsync()
        } catch (e: Exception) { err = tr("Cannot play this video: ", "تعذّر تشغيل هذا الفيديو: ") + (e.message ?: e.javaClass.simpleName) }
    }
    LaunchedEffect(ready) {
        while (ready) {
            try { pos = mp.currentPosition; playing = mp.isPlaying } catch (e: IllegalStateException) { }
            delay(400)
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    keepScreenOn = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            runCatching { mp.setSurface(null) }; surface?.release(); surface = null; return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.align(Alignment.Center).aspectRatio(ratio))
        val e = err
        if (e != null) Text(e, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        else if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
        if (ready && e == null) {
            val max = dur.toFloat().coerceAtLeast(1f)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    try { if (mp.isPlaying) { mp.pause(); playing = false } else { mp.start(); playing = true } } catch (ex: IllegalStateException) { }
                }) { Ico(if (playing) R.drawable.ic_pause else R.drawable.ic_play, 24.dp, Color.White) }
                Text(mmss(dragging?.toInt() ?: pos), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Slider(value = (dragging ?: pos.toFloat()).coerceIn(0f, max), onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { v -> try { mp.seekTo(v.toInt()); pos = v.toInt() } catch (ex: IllegalStateException) { } }
                        dragging = null
                    }, valueRange = 0f..max, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(mmss(dur), color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
