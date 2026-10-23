package com.abdo.ps4monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Cover analysis and the coordinated notification palette. Pure Kotlin on purpose (no android.* classes), so it can be unit-tested on a plain JVM.
 * Colours are ARGB ints.
 */
object ColorMath {
    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)

    /** hue 0..360, saturation 0..1, lightness 0..1 */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val l = (mx + mn) / 2f
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return floatArrayOf(if (h < 0) h + 360f else h, s.coerceIn(0f, 1f), l)
    }
    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f; val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * ll - 1f)) * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f)); val m = ll - c / 2f
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f); hh < 120f -> Triple(x, c, 0f); hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c); hh < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255f + 0.5f).toInt(), ((g + m) * 255f + 0.5f).toInt(), ((b + m) * 255f + 0.5f).toInt())
    }
    /** WCAG relative luminance */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }
    /** WCAG contrast ratio of two opaque colours (1..21) */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    fun blend(a: Int, b: Int, t: Float): Int =
        rgb((red(a) + (red(b) - red(a)) * t).toInt(), (green(a) + (green(b) - green(a)) * t).toInt(), (blue(a) + (blue(b) - blue(a)) * t).toInt())
}

/** What the cover looks like: its dominant colour, a clearly different second colour (or null), how much of it is colourful, and its mean brightness. */
class CoverAnalysis(val primary: Int, val secondary: Int?, val colorfulness: Float, val meanLuma: Float)

/**
 * The colours of one download card. Every role has a job, so the card has a hierarchy and is not just "one colour everywhere":
 *  surface / onSurface (title) / onSurfaceVariant (sizes, time left) - background and text, dark-on-light or light-on-dark, contrast >= 4.5:1
 *  accent (progress fill, >= 3:1 as a graphic) / accentText (percentage, >= 4.5:1) / tertiary (speed; second cover colour when there is one)
 *  track (ARGB tint of the empty part of the bar) / container + onContainer (button backgrounds and what is on them) / warn (confirm delete) / dim (paused fill)
 */
class Palette(
    val dark: Boolean, val surface: Int, val onSurface: Int, val onSurfaceVariant: Int,
    val accent: Int, val accentText: Int, val tertiary: Int,
    val track: Int, val container: Int, val onContainer: Int, val warn: Int, val dim: Int,
    /** background colour for a colourized media-style notification (Android picks the text colour for it) */ val media: Int)

object CoverPalette {
    private const val BINS = 12

    private fun hsv(c: Int, out: FloatArray) {
        val r = ColorMath.red(c) / 255f; val g = ColorMath.green(c) / 255f; val b = ColorMath.blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        out[2] = mx; out[1] = if (mx == 0f) 0f else d / mx
        val h = if (d == 0f) 0f else when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        out[0] = if (h < 0) h + 360f else h
    }
    private fun hueDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return min(d, 360f - d) }

    /** Dominant colour = the most colourful hue (grey / near-black pixels ignored, bins centred on 0, 30, 60 ... degrees so red is one bin). */
    fun analyze(px: IntArray): CoverAnalysis {
        val w = DoubleArray(BINS); val r = DoubleArray(BINS); val g = DoubleArray(BINS); val b = DoubleArray(BINS)
        var n = 0; var colorful = 0; var lumaSum = 0.0
        var ar = 0.0; var ag = 0.0; var ab = 0.0
        val t = FloatArray(3)
        for (p in px) {
            if (ColorMath.alpha(p) < 128) continue
            n++; lumaSum += ColorMath.luminance(p)
            ar += ColorMath.red(p); ag += ColorMath.green(p); ab += ColorMath.blue(p)
            hsv(p, t)
            if (t[2] < 0.2f || t[1] < 0.25f) continue
            colorful++
            val wt = (t[1] * t[2]).toDouble()
            val bin = (((t[0] + 180f / BINS) % 360f) / (360f / BINS)).toInt().coerceIn(0, BINS - 1)
            w[bin] += wt; r[bin] += ColorMath.red(p) * wt; g[bin] += ColorMath.green(p) * wt; b[bin] += ColorMath.blue(p) * wt
        }
        if (n == 0) return CoverAnalysis(0xFF3F51B5.toInt(), null, 0f, 0.3f)
        fun binColor(i: Int) = ColorMath.rgb((r[i] / w[i]).toInt(), (g[i] / w[i]).toInt(), (b[i] / w[i]).toInt())
        var best = 0; for (i in 1 until BINS) if (w[i] > w[best]) best = i
        if (w[best] <= 0.0) return CoverAnalysis(ColorMath.rgb((ar / n).toInt(), (ag / n).toInt(), (ab / n).toInt()), null, 0f, (lumaSum / n).toFloat())
        val primary = binColor(best)
        val ph = ColorMath.toHsl(primary)[0]
        var second = -1
        for (i in 0 until BINS) {
            if (i == best || w[i] < w[best] * 0.35) continue
            if (hueDist(ph, ColorMath.toHsl(binColor(i))[0]) < 60f) continue
            if (second < 0 || w[i] > w[second]) second = i
        }
        return CoverAnalysis(primary, if (second >= 0) binColor(second) else null, colorful.toFloat() / n, (lumaSum / n).toFloat())
    }

    /** Lifts (dark surface) or lowers (light surface) the lightness of a hue until it reaches [minContrast] against [bg]. */
    private fun tone(h: Float, s: Float, startL: Float, bg: Int, minContrast: Double, lighter: Boolean): Int {
        var l = startL; var c = ColorMath.fromHsl(h, s, l)
        while (ColorMath.contrast(c, bg) < minContrast) {
            l += if (lighter) 0.025f else -0.025f
            if (l > 0.97f || l < 0.03f) return if (lighter) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            c = ColorMath.fromHsl(h, s, l)
        }
        return c
    }

    /**
     * [allowLight]: a light surface (dark text) is allowed for bright covers (yellow, cyan, lime ...). The caller passes false where the
     * progress bar cannot be tinted (below Android 12), because the fallback bar there is white and would vanish on a light card.
     */
    fun build(a: CoverAnalysis, allowLight: Boolean): Palette {
        val hsl = ColorMath.toHsl(a.primary)
        val h = hsl[0]
        // a cover that is mostly black / grey (little colour) gets a quiet, desaturated palette instead of an over-saturated one from a few pixels
        val vivid = 0.15f + 0.85f * min(1f, a.colorfulness / 0.25f)
        val sat = hsl[1] * vivid
        val light = allowLight && a.colorfulness >= 0.30f && ColorMath.luminance(a.primary) >= 0.45
        val h2 = a.secondary?.let { ColorMath.toHsl(it) }
        return if (!light) {
            val surface = ColorMath.fromHsl(h, sat.coerceIn(0.10f, 0.55f), 0.16f)
            val onS = tone(h, 0.20f, 0.96f, surface, 7.0, true)
            val onV = tone(h, 0.15f, 0.76f, surface, 4.5, true)
            val acS = (sat + 0.10f).coerceIn(0.20f, 0.90f)
            val accent = tone(h, acS, 0.58f, surface, 3.0, true)
            val accentText = tone(h, acS, 0.64f, surface, 4.5, true)
            val tert = if (h2 != null) tone(h2[0], h2[1].coerceIn(0.45f, 0.85f), 0.64f, surface, 4.5, true) else accentText
            Palette(true, surface, onS, onV, accent, accentText, tert,
                ColorMath.withAlpha(accent, 0x99), ColorMath.fromHsl(h, sat.coerceIn(0.10f, 0.50f), 0.26f), onS,
                0xFFFFB74D.toInt(), ColorMath.blend(surface, accent, 0.45f),
                ColorMath.fromHsl(h, sat.coerceIn(0.25f, 0.65f), 0.30f))
        } else {
            val surface = ColorMath.fromHsl(h, sat.coerceIn(0.45f, 0.85f), 0.88f)
            val onS = tone(h, 0.45f, 0.10f, surface, 7.0, false)
            val onV = tone(h, 0.30f, 0.30f, surface, 4.5, false)
            val acS = (sat + 0.05f).coerceIn(0.45f, 0.95f)
            val accent = tone(h, acS, 0.46f, surface, 3.0, false)
            val accentText = tone(h, acS, 0.40f, surface, 4.5, false)
            val tert = if (h2 != null) tone(h2[0], h2[1].coerceIn(0.45f, 0.85f), 0.38f, surface, 4.5, false) else accentText
            Palette(false, surface, onS, onV, accent, accentText, tert,
                ColorMath.withAlpha(accent, 0x99), ColorMath.fromHsl(h, sat.coerceIn(0.40f, 0.80f), 0.78f), onS,
                0xFF8F4A00.toInt(), ColorMath.blend(surface, accent, 0.45f), surface)
        }
    }
}
