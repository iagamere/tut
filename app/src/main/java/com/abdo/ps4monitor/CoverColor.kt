package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The colours of a game's cover (icon0.png of the PKG), used to tint cards and notifications. No extra library.
 * The analysis (and the palette built from it, see [CoverPalette]) is cached per download and recomputed when the cover changes:
 * the cache entry carries a signature of the cover state, so a new cover never keeps showing the old colours.
 */
object CoverColor {
    private class Entry(val sig: String, val analysis: CoverAnalysis) {
        @Volatile var pal: Palette? = null
        @Volatile var palLight = false
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val fallbackPalettes = ConcurrentHashMap<Long, Palette>()

    private fun sig(d: Download) = "${d.iconReady}|${d.titleId}|${d.pkgTitle}"

    private fun entry(d: Download): Entry? {
        if (!d.iconReady) return null
        val s = sig(d)
        entries[d.id]?.let { if (it.sig == s) return it }
        val bmp = PkgStore.bitmap("d:${d.id}", "icon0.png", 48) ?: return null     // unreadable: not cached, tried again next time
        val e = Entry(s, CoverPalette.analyze(pixels(bmp)))
        entries[d.id] = e
        return e
    }
    private fun pixels(bmp: Bitmap): IntArray { val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height); return px }

    /** ARGB dominant colour kept readable (not too pale, not too dark), or null while the cover is not available yet. Disk access: call off the main thread (or use [load]). */
    fun forDownload(d: Download): Int? {
        val a = entry(d)?.analysis ?: return null
        val hsv = FloatArray(3); Color.colorToHSV(a.primary, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.40f, 0.70f)
        return Color.HSVToColor(hsv)
    }
    suspend fun load(d: Download): Int? = withContext(Dispatchers.IO) { forDownload(d) }
    fun forget(id: String) { entries.remove(id) }

    /**
     * The notification palette of a download: from its cover, or (no cover yet / unreadable) from [fallback], a colour derived from the file name.
     * Cached: the bitmap is analysed once per cover, the palette once per (cover, allowLight).
     */
    fun palette(d: Download, fallback: Int, allowLight: Boolean): Palette {
        val e = entry(d)
        if (e == null) {
            val key = (fallback.toLong() and 0xFFFFFFFFL) or (if (allowLight) 1L shl 40 else 0L)
            return fallbackPalettes.getOrPut(key) { CoverPalette.build(CoverAnalysis(fallback, null, 0.5f, 0.3f), allowLight) }
        }
        e.pal?.let { if (e.palLight == allowLight) return it }
        val p = CoverPalette.build(e.analysis, allowLight)
        e.pal = p; e.palLight = allowLight
        return p
    }
}
