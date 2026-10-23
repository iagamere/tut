package com.abdo.ps4monitor
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Channels:
 *  - "dl": ONE card per download (active or paused). A custom RemoteViews layout (DecoratedCustomViewStyle, so the system keeps the app header):
 *    a card in a dark tone of the game's cover colour, the cover, the title, a REAL determinate ProgressBar whose fill is the cover colour,
 *    percentage, downloaded / total, size left, time left, speed, and two buttons: Pause <-> Resume, and Delete (asks for a second tap).
 *    Android does not colour a normal notification's background (setColorized only works for foreground-service notifications, or media
 *    notifications with a MediaSession), so the colour lives inside the card, which the app fully controls.
 *  - "monitor_min": the mandatory foreground-service notification (the one place where setColorized is honoured): one quiet line with the
 *    overall progress; colourized with the cover colour while exactly one download is active.
 *  - "alerts": a heads-up alert when a download has been interrupted for a while (one per incident, removed when it recovers).
 *  - "done": one notification per finished / failed download, grouped.
 * There is no per-download progress notification any more (that is what flooded the shade).
 */
object Notifier {
    private lateinit var ctx: Context
    private var lastSig = ""
    private val troubleSince = ConcurrentHashMap<Int, Long>()
    private val alerted = ConcurrentHashMap<Int, Long>()
    private const val ALERT_BASE = 500_000
    private const val SERVICE_CHANNEL = "monitor_hidden"
    private const val DL_CHANNEL = "dl_v2"
    const val ACT_PAUSE = "com.abdo.ps4monitor.NOTIF_PAUSE"
    const val ACT_RESUME = "com.abdo.ps4monitor.NOTIF_RESUME"
    const val ACT_DELETE = "com.abdo.ps4monitor.NOTIF_DELETE"
    private const val CONFIRM_MS = 6_000L
    private val confirmDel = ConcurrentHashMap<String, Long>()     // download id -> time until which the next Delete tap is the confirmation
    private val posted = ConcurrentHashMap<Int, String>()          // notification id -> signature of the card currently shown

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        runCatching { m.deleteNotificationChannel("active"); m.deleteNotificationChannel("results") }     // channels of the old version
        runCatching { m.deleteNotificationChannel("monitor") }     // channel of 4.2 (its notification was the big combined one)
        // Download cards: importance DEFAULT (not LOW), so they are NOT in the "Silent" section and show on the lock screen, but with no sound and no
        // vibration, and (onlyAlertOnce) updates never alert. A channel's importance cannot be changed after creation, hence the new id "dl_v2".
        runCatching { m.deleteNotificationChannel("dl") }                  // (fails while the old service uses it: harmless, it is just unused)
        m.createNotificationChannel(NotificationChannel(DL_CHANNEL, tr("Downloads", "التحميلات"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        // The mandatory foreground-service notification lives on a channel that is created BLOCKED (IMPORTANCE_NONE): the service keeps running, but
        // the notification is not shown in the shade, so only the download cards remain. (The user can still enable it: Settings > Notifications.)
        runCatching { m.deleteNotificationChannel("monitor_min") }        // the visible channel of 4.3 (may fail while the old service is alive: harmless)
        m.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, tr("Monitoring (service, hidden)", "المراقبة (الخدمة، مخفية)"), NotificationManager.IMPORTANCE_NONE).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("alerts", tr("Interruptions", "انقطاع التحميل"), NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel("done", tr("Finished downloads", "التحميلات المنتهية"), NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pi() = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    private fun psName(d: Download) = Ps4Repo.get(d.ps4Id)?.name.orEmpty()
    private fun gameTitle(d: Download) = d.pkgTitle ?: d.displayName
    private fun cover(d: Download) = if (d.iconReady) PkgStore.bitmap("d:${d.id}", "icon0.png", 128) else null

    private fun activeList() = DownloadRepo.all.value.filter { it.state.active && it.state != DlState.SUBMITTING && !it.superseded }

    private fun oneLine(d: Download): String {
        val s = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(d.pct?.let { "$it%" }, Fmt.mbs(d.speed)).joinToString(" • ")
            DlState.STALLED -> tr("stalled", "متعثّر")
            DlState.SERVER_DOWN -> if (DownloadMonitor.status.value[d.ps4Id]?.power == Power.REST) tr("PS4 in rest mode", "الـPS4 في وضع الراحة") else tr("server not reachable", "الخادم غير متاح")
            DlState.QUEUED -> tr("queued", "في الانتظار")
            else -> d.state.label
        }
        return "${gameTitle(d).take(34)} — $s"
    }

    /** The mandatory foreground-service notification (id 1): one quiet line. The per-download cards carry the details. */
    fun summary(c: Context, fallback: String): Notification {
        val act = activeList()
        val b = NotificationCompat.Builder(c, SERVICE_CHANNEL).setSmallIcon(R.drawable.ic_stat_ps4).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setContentIntent(pi()).setPriority(NotificationCompat.PRIORITY_MIN).setContentTitle("PS4 Monitor")
        if (act.isEmpty() || !Store.settings().notif) return b.setContentText(fallback).build()
        val speed = act.filter { it.state == DlState.DOWNLOADING }.sumOf { it.speed }
        val trouble = act.any { it.state == DlState.STALLED || it.state == DlState.SERVER_DOWN }
        val head = if (act.size == 1) tr("1 download", "تحميل واحد") else tr("${act.size} downloads", "${act.size} تحميلات")
        val line = listOfNotNull(head, if (speed > 0) Fmt.mbs(speed) else null, if (trouble) tr("interrupted - retrying", "منقطع - تجري إعادة المحاولة") else null).joinToString(" • ")
        // overall progress as the system's own (natively tinted) progress bar
        val known = act.filter { (it.expectedSize ?: 0L) > 0L }
        val total = known.sumOf { it.expectedSize ?: 0L }
        if (total > 0L) b.setProgress(1000, (known.sumOf { minOf(it.currentSize, it.expectedSize ?: 0L) } * 1000L / total).toInt().coerceIn(0, 1000), false)
        // colour: the downloading card if there is one. Colourized only for a single download (with several, one colour would be arbitrary).
        val lead = act.firstOrNull { it.state == DlState.DOWNLOADING } ?: act.first()
        val pal = palette(lead)
        b.setColor(if (act.size == 1) pal.surface else pal.accentText)
        if (act.size == 1) b.setColorized(true)       // honoured here because this notification is the foreground-service one
        return b.setContentText(line).build()
    }
    // ---------------------------------------------------------------------------------------------
    // Foreground state: Android requires a notification for the service and cannot hide it (it even ignores a blocked channel), so the foreground
    // notification is the LEAD download's card. The quiet summary (id 1) is only used while there is no card to carry it.
    // ---------------------------------------------------------------------------------------------
    @Volatile var fgId = 1
    @Volatile private var svc: MonitorService? = null
    private var lastFallback = ""
    fun attach(s: MonitorService) { svc = s }
    fun detach() { svc = null; fgId = 1 }
    fun onPromoted(id: Int) { fgId = id }

    private fun leadDownload(excluding: String? = null): Download? {
        if (!Store.settings().notif) return null
        val l = DownloadRepo.all.value.filter { cardWanted(it) && it.id != excluding }
        return l.firstOrNull { it.state != DlState.PAUSED } ?: l.firstOrNull()
    }
    private fun cardFor(d: Download): Pair<Int, Notification> { posted[d.notificationId] = cardSig(d); return d.notificationId to card(d) }

    fun foregroundFor(c: Context, fallback: String): Pair<Int, Notification> {
        lastFallback = fallback
        val lead = leadDownload()
        return if (lead != null) cardFor(lead) else 1 to summary(c, fallback)
    }
    /** Every service tick: if another download should carry the foreground state (the lead finished, was deleted ...), move it there. */
    fun syncForeground(fallback: String) {
        val s = svc ?: return
        lastFallback = fallback
        val want = leadDownload()?.notificationId ?: 1
        if (want == fgId) return
        val (id, n) = foregroundFor(s, fallback)
        s.promote(id, n); fgId = id
    }
    /** The card that carries the foreground state is about to be replaced / removed: hand the state to the next card first (a foreground notification cannot be cancelled). */
    private fun handoff(leavingNotifId: Int) {
        val s = svc ?: return
        if (leavingNotifId != fgId) return
        val leaving = DownloadRepo.all.value.firstOrNull { it.notificationId == leavingNotifId }
        val next = leadDownload(excluding = leaving?.id)
        val (id, n) = if (next != null) cardFor(next) else 1 to summary(s, lastFallback)
        s.promote(id, n); fgId = id
    }

    /** Re-posts the ongoing notification only when something visible changed. */
    fun refreshSummary(c: Context, fallback: String) {
        if (fgId != 1) return          // a card carries the foreground state: no separate service row
        val sig = activeList().joinToString(";") { "${it.id}:${it.state}:${it.pct}:${(it.speed / 262144).toInt()}:${it.etaSec / 20}:${it.iconReady}" } + "|" + fallback + "|" + Store.settings().notif
        if (sig == lastSig) return
        lastSig = sig
        post(1, summary(c, fallback))
    }

    /** Call for every active download on each poll: raises ONE alert when the trouble lasted long enough. */
    fun watchTrouble(d: Download) {
        if (!Store.sp.getBoolean("alert_int", true) || !Store.settings().notif) return
        val id = d.notificationId; val now = System.currentTimeMillis()
        val bad = d.state == DlState.STALLED || d.state == DlState.SERVER_DOWN
        if (!bad) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id); return }
        val since = troubleSince.getOrPut(id) { now }
        val limit = if (d.state == DlState.SERVER_DOWN) 90_000L else 180_000L
        if (now - since < limit) return
        val last = alerted[id]
        if (last != null && now - last < 30 * 60_000L) return          // remind at most every 30 minutes
        alerted[id] = now
        post(id + ALERT_BASE, alertNotification(d, now - since))
    }
    private fun alertNotification(d: Download, trouble: Long): Notification {
        val mins = (trouble / 60_000L).coerceAtLeast(1L)
        val why = if (d.state == DlState.SERVER_DOWN && DownloadMonitor.status.value[d.ps4Id]?.power == Power.REST)
            tr("The PS4 is in rest mode and PS4 server is not answering. Wake the PS4 from the app.", "الـPS4 في وضع الراحة وخادم PS4 لا يستجيب. أيقظ الـPS4 من التطبيق.")
        else if (d.state == DlState.SERVER_DOWN)
            tr("PS4 server on the PS4 is not answering (the PS4 may be asleep, or the server stopped).", "خادم PS4 على الـPS4 لا يستجيب (قد يكون الجهاز في وضع الراحة أو توقف الخادم).")
        else tr("No download progress is being made.", "لا يوجد تقدّم في التحميل.")
        val at = listOfNotNull(d.pct?.let { "$it%" }, if (d.currentSize > 0) Fmt.gb(d.currentSize) else null).joinToString(" • ")
        val body = why + "\n" + tr("Interrupted for $mins min", "منقطع منذ $mins د") + (if (at.isNotEmpty()) " • $at" else "") + "\n" + tr("Tap to open the app.", "اضغط لفتح التطبيق.")
        val b = NotificationCompat.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(tr("Download interrupted", "انقطع التحميل") + ": " + gameTitle(d)).setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setSubText(psName(d).ifEmpty { null })
            .setAutoCancel(true).setContentIntent(pi()).setCategory(NotificationCompat.CATEGORY_ERROR).setPriority(NotificationCompat.PRIORITY_HIGH)
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        styleStatus(b, d, tr("Download interrupted", "انقطع التحميل") + " • " + tr("$mins min", "$mins د") + (if (at.isNotEmpty()) " • $at" else ""), why, d.frac ?: 0f, true)
        return b.build()
    }

    /** Completed / failed / not started: posted once (guarded by Download.terminalNotified), grouped. */
    fun result(d: Download) {
        handoff(d.notificationId)
        clearTrouble(d.notificationId)
        if (!Store.settings().notif) return
        val ok = d.state == DlState.COMPLETED
        val secs = if (d.startedAt > 0 && d.completedAt > d.startedAt) (d.completedAt - d.startedAt) / 1000L else 0L
        val head = when {
            ok -> tr("Download completed", "اكتمل التحميل")
            d.state == DlState.FAILED -> tr("Download failed", "فشل التحميل")
            else -> tr("Download has not started yet", "لم يبدأ التحميل بعد")
        }
        val body = when {
            ok -> listOfNotNull(Fmt.gb(d.currentSize), if (secs > 0) tr("in ${Fmt.dur(secs)}", "خلال ${Fmt.dur(secs)}") else null, tr("verified", "تم التحقق")).joinToString(" • ")
            d.state == DlState.FAILED -> Tx.t(d.errorMessage ?: tr("Unknown reason", "سبب غير معروف"))
            else -> tr("PS4 server accepted the request but no download activity was detected.", "قبل PS4 server الطلب لكن لم يُكتشف أي نشاط تحميل.")
        }
        val b = NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done")
            .setContentTitle(gameTitle(d)).setContentText("$head • $body").setSubText(psName(d).ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$head\n$body")).setAutoCancel(true).setContentIntent(pi())
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        styleStatus(b, d, head, body, if (ok) 1f else (d.frac ?: 0f), !ok)
        releaseSession(d.notificationId)
        post(d.notificationId, b.build())
        post(2, NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done").setGroupSummary(true)
            .setContentTitle(tr("PS4 downloads", "تحميلات PS4")).setAutoCancel(true).setContentIntent(pi()).build())
    }

    /**
     * "Finished" and "interrupted" notifications in the same look as the download cards: cover colour background, cover, name, text, bar.
     * Reuses the collapsed card layout with its two buttons hidden; the expanded form lets the text wrap over several lines.
     * Any failure here leaves the plain notification as it was.
     */
    private fun styleStatus(b: NotificationCompat.Builder, d: Download, head: String, line: String, frac: Float, warn: Boolean) {
        try {
            val pal = palette(d)
            fun view(lines: Int): RemoteViews {
                val rv = RemoteViews(ctx.packageName, if (FULL_BLEED) R.layout.notif_dl_collapsed_fb else R.layout.notif_dl_collapsed)
                rv.setInt(R.id.bg, "setColorFilter", pal.surface)
                rv.setImageViewBitmap(R.id.cover, coverBitmap(d, pal))
                rv.setTextViewText(R.id.title, gameTitle(d)); rv.setTextColor(R.id.title, pal.onSurface)
                rv.setTextViewText(R.id.stats, "$head\n$line".trim()); rv.setTextColor(R.id.stats, if (warn) pal.warn else pal.onSurfaceVariant)
                rv.setInt(R.id.stats, "setMaxLines", lines)
                rv.setProgressBar(R.id.bar, 1000, (frac * 1000f).toInt().coerceIn(0, 1000), false)
                if (Build.VERSION.SDK_INT >= 31) {
                    rv.setColorStateList(R.id.bar, "setProgressTintList", ColorStateList.valueOf(if (warn) pal.warn else pal.accent))
                    rv.setColorStateList(R.id.bar, "setProgressBackgroundTintList", ColorStateList.valueOf(pal.track))
                }
                rv.setViewVisibility(R.id.ib_toggle, android.view.View.GONE)
                rv.setViewVisibility(R.id.ib_delete, android.view.View.GONE)
                return rv
            }
            b.setColor(pal.accentText).setCustomContentView(view(1)).setCustomBigContentView(view(6))
            b.setStyle(if (FULL_BLEED) null else NotificationCompat.DecoratedCustomViewStyle())
        } catch (e: Exception) { DownloadMonitor.d("Status notification styling failed: ${e.javaClass.simpleName}") }
    }

    private fun cancelAlert(id: Int) { runCatching { NotificationManagerCompat.from(ctx).cancel(id + ALERT_BASE) } }
    fun clearTrouble(id: Int) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id) }
    fun cancel(id: Int) { handoff(id); clearTrouble(id); posted.remove(id); releaseSession(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    // ---------------------------------------------------------------------------------------------
    // Download cards (music-player style)
    // ---------------------------------------------------------------------------------------------
    private fun cardWanted(d: Download) = !d.superseded && d.state != DlState.SUBMITTING && (d.state.active || d.state == DlState.PAUSED)
    private fun confirming(id: String) = (confirmDel[id] ?: 0L) > System.currentTimeMillis()

    /** No cover yet: a stable colour taken from the file name, so the card is still coloured. */
    private fun fallbackColor(d: Download): Int =
        Color.HSVToColor(floatArrayOf(((d.displayName.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.55f))

    /** Colours of this download's card (cover colour, cached in [CoverColor]; no bitmap is decoded again for an already known cover). */
    private fun palette(d: Download): Palette = CoverColor.palette(d, fallbackColor(d), Build.VERSION.SDK_INT >= 31)

    private class CardText(val pct: String?, val size: String, val left: String, val speed: String?, val sub: String) {
        /** one line for the collapsed card (and the plain content text) */
        val line get() = listOfNotNull(pct, size).joinToString(" • ")
    }

    /** "53.72 / 64.92 GB" when both values have the same unit, "953.20 MB / 64.92 GB" otherwise */
    private fun sizePair(cur: Long, total: Long): String {
        val a = Fmt.bytes(cur); val b = Fmt.bytes(total)
        return if (a.substringAfterLast(' ') == b.substringAfterLast(' ')) a.substringBeforeLast(' ') + " / " + b else "$a / $b"
    }

    private fun cardTexts(d: Download): CardText {
        val total = d.expectedSize ?: 0L
        val left = if (total > 0L) (total - d.currentSize).coerceAtLeast(0L) else 0L
        val size = if (total > 0L) sizePair(d.currentSize, total) else Fmt.bytes(d.currentSize)
        val leftTxt = if (left > 0L) "${Fmt.bytes(left)} ${tr("left", "متبقٍ")}" else null
        val speed = if (d.state == DlState.DOWNLOADING && d.speed > 0) Fmt.mbs(d.speed) else null
        val leftLine = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(leftTxt, if (d.etaSec >= 0) Fmt.dur(d.etaSec) else null).joinToString(" • ")
            DlState.PAUSED -> listOfNotNull(tr("Paused", "متوقف مؤقتًا"), leftTxt).joinToString(" • ")
            else -> listOfNotNull(d.state.label, leftTxt).joinToString(" • ")
        }
        // notification header: kept SHORT (the card shows the rest), otherwise the system truncates the app name ("PS4 Download…")
        val sub = when (d.state) {
            DlState.DOWNLOADING -> if (d.etaSec >= 0) Fmt.dur(d.etaSec) else ""
            DlState.PAUSED -> tr("Paused", "متوقف مؤقتًا")
            else -> d.state.label
        }
        return CardText(d.pct?.let { "$it%" }, size, leftLine, speed, sub)
    }

    private fun actionPi(act: String, d: Download): PendingIntent {
        // PendingIntents are told apart by action / data, not by extras: the id goes into the data so two downloads never share one
        val i = Intent(ctx, NotifActionReceiver::class.java).setAction(act).setData(Uri.parse("ps4mon://download/${d.id}/$act")).putExtra("id", d.id)
        return PendingIntent.getBroadcast(ctx, d.notificationId * 4 + (if (act == ACT_DELETE) 1 else 0), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---- cover bitmap: square, rounded, made once per download / state of the cover and kept in a small cache ----
    private val coverCache = LruCache<String, Bitmap>(16)
    private const val COVER_PX = 128

    private fun coverBitmap(d: Download, pal: Palette): Bitmap {
        val key = "${d.id}:${d.iconReady}:${d.titleId}"
        coverCache.get(key)?.let { return it }
        val src = cover(d)
        val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = COVER_PX * 0.2f
        val all = RectF(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat())
        if (src != null && src.width > 0 && src.height > 0) {
            c.drawRoundRect(all, r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val s = minOf(src.width, src.height)
            c.drawBitmap(src, Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2), Rect(0, 0, COVER_PX, COVER_PX), p)
        } else {
            // missing / unreadable cover: a tinted tile with the download glyph
            p.color = pal.container; c.drawRoundRect(all, r, r, p)
            ContextCompat.getDrawable(ctx, R.drawable.ic_download)?.mutate()?.let { g ->
                val m = COVER_PX / 4
                g.setBounds(m, m, COVER_PX - m, COVER_PX - m); g.setTint(pal.accentText); g.draw(c)
            }
        }
        // cached per download; the key changes when iconReady flips, so a placeholder is replaced by the real cover
        coverCache.put(key, out)
        return out
    }
    fun forgetCover(id: String) { coverCache.snapshot().keys.filter { it.startsWith("$id:") }.forEach { coverCache.remove(it) } }

    /** Fill and track of the real ProgressBar in the cover colour. See res/drawable/notif_progress.xml for what happens below Android 12. */
    private fun styleBar(rv: RemoteViews, d: Download, pal: Palette) {
        val indeterminate = d.frac == null && d.state == DlState.DOWNLOADING      // total size not known yet
        rv.setProgressBar(R.id.bar, 1000, ((d.frac ?: 0f) * 1000f).toInt().coerceIn(0, 1000), indeterminate)
        if (Build.VERSION.SDK_INT >= 31) {      // RemoteViews.setColorStateList: API 31. ProgressBar.setProgressTintList & co. are @RemotableViewMethod.
            val fill = ColorStateList.valueOf(if (d.state == DlState.PAUSED) pal.dim else pal.accent)
            rv.setColorStateList(R.id.bar, "setProgressTintList", fill)
            rv.setColorStateList(R.id.bar, "setProgressBackgroundTintList", ColorStateList.valueOf(pal.track))
            rv.setColorStateList(R.id.bar, "setIndeterminateTintList", fill)
        }
    }

    private fun collapsedView(d: Download, pal: Palette, t: CardText, paused: Boolean, confirm: Boolean, fb: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, if (fb) R.layout.notif_dl_collapsed_fb else R.layout.notif_dl_collapsed)
        rv.setInt(R.id.bg, "setColorFilter", pal.surface)
        rv.setImageViewBitmap(R.id.cover, coverBitmap(d, pal))
        rv.setTextViewText(R.id.title, gameTitle(d)); rv.setTextColor(R.id.title, pal.onSurface)
        rv.setTextViewText(R.id.stats, if (confirm) tr("Tap again to delete download + file", "اضغط مرة أخرى لحذف التحميل وملفه") else t.line)
        rv.setTextColor(R.id.stats, if (confirm) pal.warn else pal.onSurfaceVariant)
        styleBar(rv, d, pal)
        val toggle = if (paused) tr("Resume", "استئناف") else tr("Pause", "إيقاف مؤقت")
        val del = if (confirm) tr("Confirm delete", "تأكيد الحذف") else tr("Delete", "حذف")
        rv.setImageViewResource(R.id.ib_toggle, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.setInt(R.id.ib_toggle, "setColorFilter", pal.onSurface)
        rv.setContentDescription(R.id.ib_toggle, toggle)
        rv.setOnClickPendingIntent(R.id.ib_toggle, actionPi(if (paused) ACT_RESUME else ACT_PAUSE, d))
        rv.setImageViewResource(R.id.ib_delete, if (confirm) R.drawable.ic_warning else R.drawable.ic_delete)
        rv.setInt(R.id.ib_delete, "setColorFilter", if (confirm) pal.warn else pal.onSurfaceVariant)
        rv.setContentDescription(R.id.ib_delete, del)
        rv.setOnClickPendingIntent(R.id.ib_delete, actionPi(ACT_DELETE, d))
        return rv
    }

    private fun expandedView(d: Download, pal: Palette, t: CardText, paused: Boolean, confirm: Boolean, fb: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, if (fb) R.layout.notif_dl_expanded_fb else R.layout.notif_dl_expanded)
        if (fb) {      // the system draws no header around a full-bleed notification: draw our own
            rv.setInt(R.id.app_glyph, "setColorFilter", pal.accentText)
            rv.setTextColor(R.id.app_name, pal.onSurfaceVariant)
            rv.setTextViewText(R.id.eta, t.sub); rv.setTextColor(R.id.eta, pal.onSurfaceVariant)
        }
        rv.setInt(R.id.bg, "setColorFilter", pal.surface)
        rv.setImageViewBitmap(R.id.cover, coverBitmap(d, pal))
        rv.setTextViewText(R.id.title, gameTitle(d)); rv.setTextColor(R.id.title, pal.onSurface)
        rv.setTextColor(R.id.state, pal.onSurfaceVariant)
        rv.setTextViewText(R.id.state, listOfNotNull(psName(d).ifEmpty { null }, if (d.state == DlState.DOWNLOADING) tr("Downloading", "جارٍ التحميل") else if (paused) tr("Paused", "متوقف مؤقتًا") else d.state.label).joinToString(" • "))
        styleBar(rv, d, pal)
        rv.setViewVisibility(R.id.pct, if (t.pct != null) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.pct, t.pct ?: ""); rv.setTextColor(R.id.pct, pal.accentText)
        rv.setTextViewText(R.id.size, t.size); rv.setTextColor(R.id.size, pal.onSurfaceVariant)
        rv.setTextViewText(R.id.left, t.left); rv.setTextColor(R.id.left, pal.onSurfaceVariant)
        rv.setTextViewText(R.id.speed, t.speed ?: ""); rv.setTextColor(R.id.speed, pal.tertiary)
        rv.setViewVisibility(R.id.hint, if (confirm) View.VISIBLE else View.GONE)
        rv.setTextColor(R.id.hint, pal.warn)
        if (confirm) rv.setTextViewText(R.id.hint, tr("Tap the bin again to delete this download and its file", "اضغط سلة المهملات مرة أخرى لحذف التحميل وملفه"))
        // buttons
        rv.setInt(R.id.btn_toggle_bg, "setColorFilter", pal.container)
        rv.setImageViewResource(R.id.btn_toggle_icon, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.setInt(R.id.btn_toggle_icon, "setColorFilter", pal.onContainer)
        rv.setTextColor(R.id.btn_toggle_label, pal.onContainer)
        rv.setTextViewText(R.id.btn_toggle_label, if (paused) tr("Resume", "استئناف") else tr("Pause", "إيقاف مؤقت"))
        rv.setOnClickPendingIntent(R.id.btn_toggle, actionPi(if (paused) ACT_RESUME else ACT_PAUSE, d))
        rv.setInt(R.id.btn_delete_bg, "setColorFilter", if (confirm) ColorMath.withAlpha(pal.warn, 0x33) else pal.container)
        rv.setImageViewResource(R.id.btn_delete_icon, if (confirm) R.drawable.ic_warning else R.drawable.ic_delete)
        rv.setInt(R.id.btn_delete_icon, "setColorFilter", if (confirm) pal.warn else pal.onContainer)
        rv.setTextViewText(R.id.btn_delete_label, if (confirm) tr("Confirm delete", "تأكيد الحذف") else tr("Delete", "حذف"))
        rv.setTextColor(R.id.btn_delete_label, if (confirm) pal.warn else pal.onContainer)
        rv.setOnClickPendingIntent(R.id.btn_delete, actionPi(ACT_DELETE, d))
        return rv
    }

    /** Fallback card (custom RemoteViews on the system's own grey surface), used when the media session cannot be created. */
    /**
     * true: no notification style -> the custom view IS the whole notification (no grey container, the cover-coloured surface runs edge to edge).
     * Android only allows this for apps with targetSdk <= 30 (docs: apps targeting 31+ cannot create fully custom notifications, the system always
     * wraps them in its standard template = the grey box), so app/build.gradle.kts has targetSdk = 30. false: DecoratedCustomViewStyle (grey box).
     */
    private const val FULL_BLEED = true

    private fun customCard(d: Download): Notification {
        val paused = d.state == DlState.PAUSED
        val confirm = confirming(d.id)
        val pal = palette(d)
        val t = cardTexts(d)
        val b = Notification.Builder(ctx, DL_CHANNEL).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_stat_ps4)
            // plain title / text: not drawn (the custom views are), but used by accessibility, the lock screen and watches
            .setContentTitle(gameTitle(d)).setContentText(t.line).setSubText(t.sub.ifEmpty { null })
            .setOngoing(!paused).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
            .setColor(pal.accentText)
            .setCustomContentView(collapsedView(d, pal, t, paused, confirm, FULL_BLEED))
            .setCustomBigContentView(expandedView(d, pal, t, paused, confirm, FULL_BLEED))
        if (!FULL_BLEED) b.setStyle(Notification.DecoratedCustomViewStyle())
        return b.build()
    }

    // ---------------------------------------------------------------------------------------------
    // Media-style card (like Musicolet): the only kind of normal notification whose WHOLE background Android colours.
    // ---------------------------------------------------------------------------------------------
    private const val CA_TOGGLE = "ps4mon.toggle"
    private const val CA_DELETE = "ps4mon.delete"
    private val sessions = ConcurrentHashMap<Int, MediaSession>()     // notification id -> session of that download's card

    /**
     * One MediaSession per card. It is NEVER set active and has no hardware-key handling: it only exists so that Android treats the card as a media
     * notification (colourized background, and the Android 13+ media player). Headset / media keys therefore keep going to your music player.
     */
    private fun session(d: Download): MediaSession = sessions.getOrPut(d.notificationId) {
        MediaSession(ctx, "ps4mon-${d.notificationId}").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { onAction(ACT_RESUME, d.id) }
                override fun onPause() { onAction(ACT_PAUSE, d.id) }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        CA_TOGGLE -> onAction(if (DownloadRepo.get(d.id)?.state == DlState.PAUSED) ACT_RESUME else ACT_PAUSE, d.id)
                        CA_DELETE -> onAction(ACT_DELETE, d.id)
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }
    private fun releaseSession(nid: Int) { sessions.remove(nid)?.let { runCatching { it.release() } } }

    private fun updateSession(d: Download, art: Bitmap, paused: Boolean, confirm: Boolean): MediaSession.Token {
        val s = session(d)
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, gameTitle(d))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, psName(d).ifEmpty { "PS4" })
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 100_000L).build())      // the media player's bar: 100 s = 100 %
        val pos = ((d.frac ?: 0f) * 100_000f).toLong()
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, pos, 0f)     // speed 0: the position never ticks by itself
            .addCustomAction(PlaybackState.CustomAction.Builder(CA_TOGGLE, if (paused) tr("Resume", "استئناف") else tr("Pause", "إيقاف مؤقت"),
                if (paused) R.drawable.ic_play else R.drawable.ic_pause).build())
            .addCustomAction(PlaybackState.CustomAction.Builder(CA_DELETE, if (confirm) tr("Confirm delete", "تأكيد الحذف") else tr("Delete", "حذف"),
                if (confirm) R.drawable.ic_warning else R.drawable.ic_delete).build())
            .build())
        return s.sessionToken
    }
    private fun cardAction(icon: Int, title: String, pi: PendingIntent) = Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()

    private fun mediaCard(d: Download): Notification {
        val paused = d.state == DlState.PAUSED
        val confirm = confirming(d.id)
        val pal = palette(d)
        val t = cardTexts(d)
        val art = coverBitmap(d, pal)
        val token = updateSession(d, cover(d) ?: art, paused, confirm)
        val text = if (confirm) tr("Tap the bin again to delete this download and its file", "اضغط سلة المهملات مرة أخرى لحذف التحميل وملفه")
                   else listOfNotNull(t.line, t.speed).joinToString(" • ")
        val b = Notification.Builder(ctx, DL_CHANNEL).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(gameTitle(d)).setContentText(text).setSubText(t.sub.ifEmpty { null })
            .setOngoing(!paused).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
            .setLargeIcon(art)
            .setColor(pal.media).setColorized(true)           // honoured because the notification carries a MediaSession token
            .addAction(if (paused) cardAction(R.drawable.ic_play, tr("Resume", "استئناف"), actionPi(ACT_RESUME, d))
                       else cardAction(R.drawable.ic_pause, tr("Pause", "إيقاف مؤقت"), actionPi(ACT_PAUSE, d)))
            .addAction(if (confirm) cardAction(R.drawable.ic_warning, tr("Confirm delete", "تأكيد الحذف"), actionPi(ACT_DELETE, d))
                       else cardAction(R.drawable.ic_delete, tr("Delete", "حذف"), actionPi(ACT_DELETE, d)))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1))
        val f = d.frac
        if (f != null) b.setProgress(1000, (f * 1000f).toInt().coerceIn(0, 1000), false)
        else if (d.state == DlState.DOWNLOADING) b.setProgress(0, 0, true)
        return b.build()
    }

    /**
     * false = RemoteViews card (cover-coloured card, real ProgressBar, all download info). The media-session card (true) was tried on ColorOS:
     * the system replaced it with its own grey media player (no cover colour, stuck 00:00 / 01:40 bar, no size / speed), so it is OFF by default.
     */
    private const val USE_MEDIA_SESSION = false

    private fun card(d: Download): Notification =
        if (USE_MEDIA_SESSION) try { mediaCard(d) } catch (e: Exception) { releaseSession(d.notificationId); customCard(d) } else customCard(d)

    private fun cardSig(d: Download) =
        "${d.state}:${d.pct}:${d.currentSize shr 26}:${(d.speed / 262144).toInt()}:${d.etaSec / 20}:${d.iconReady}:${d.pkgTitle}:${confirming(d.id)}:${Lang.isAr}"

    /** Posts / updates / removes the cards. Cheap: a card is re-posted only when something visible changed. */
    fun refreshDownloads() {
        if (!::ctx.isInitialized) return
        val all = DownloadRepo.all.value
        if (!Store.settings().notif) { posted.keys.toList().forEach { cancel(it) }; return }
        val ids = HashSet<Int>()
        for (d in all) {
            if (!cardWanted(d)) continue
            ids += d.notificationId
            val sig = cardSig(d)
            if (posted[d.notificationId] == sig) continue
            posted[d.notificationId] = sig
            post(d.notificationId, card(d))
        }
        for (id in posted.keys.toList()) {
            if (id in ids) continue
            posted.remove(id); releaseSession(id)
            // a finished download's card was already replaced by the result notification (same id); only remove cards that have no owner
            val owner = all.firstOrNull { it.notificationId == id }
            if (owner == null || owner.superseded) runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
        }
    }

    /** Buttons of a card (called by [NotifActionReceiver]). Same operations as the buttons in the app. */
    fun onAction(action: String, id: String) {
        if (!::ctx.isInitialized) return
        if (DownloadRepo.get(id) == null) return
        when (action) {
            ACT_PAUSE -> Ops.launch(tr("Pausing…", "إيقاف مؤقت…")) { DownloadMonitor.pause(id) }
            ACT_RESUME -> Ops.launch(tr("Preparing resume…", "تجهيز الاستئناف…")) { DownloadMonitor.resume(id) }
            ACT_DELETE -> {
                if (confirming(id)) {
                    confirmDel.remove(id)
                    Ops.launch(tr("Deleting…", "جارٍ الحذف…")) { DownloadMonitor.delete(id, true) }
                } else {
                    confirmDel[id] = System.currentTimeMillis() + CONFIRM_MS
                    refreshDownloads()
                    Handler(Looper.getMainLooper()).postDelayed({ refreshDownloads() }, CONFIRM_MS + 300L)
                }
            }
        }
    }

    private fun post(id: Int, n: Notification) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
