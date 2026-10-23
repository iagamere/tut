package com.abdo.ps4monitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import android.content.Context
import android.net.Uri
import java.io.File

/** What the console itself says about its power state (DDP, UDP port 987). */
enum class Power(private val en: String, private val ar: String) {
    UNKNOWN("Not checked", "لم يُفحص"), ON("On", "يعمل"), REST("Rest mode", "وضع الراحة"), OFF("No answer", "لا يستجيب");
    val label get() = tr(en, ar)
}

/**
 * PS4 "device discovery protocol" (DDP). It is answered by the console's system software itself, so it works in rest mode
 * (when "Stay Connected to the Internet" / "Enable Turning On PS4 from Network" are on in the PS4's Power Save Settings):
 *   request  "SRCH * HTTP/1.1"  ->  "HTTP/1.1 200 Ok" (on)  or  "HTTP/1.1 620 Server Standby" (rest mode)
 * Waking needs the console's registration key ("user-credential") from a registered Remote Play / Second Screen client.
 * NOT tested on a real console by the author.
 */
object Ps4Power {
    private const val PORT = 987
    private const val VERSION = "00020020"

    /** One DDP question. Two tries, because a single UDP packet can be lost on Wi-Fi. */
    suspend fun probe(host: String, timeoutMs: Int = 1200): Power = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Power.UNKNOWN
        repeat(2) {
            val r = askOnce(host, "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:$VERSION\n", timeoutMs)
            if (r != null) {
                val code = Regex("^HTTP/1\\.1\\s+(\\d{3})").find(r.trimStart())?.groupValues?.get(1)
                return@withContext when (code) { "200" -> Power.ON; "620" -> Power.REST; else -> Power.UNKNOWN }
            }
        }
        Power.OFF
    }

    private fun askOnce(host: String, msg: String, timeoutMs: Int): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val data = msg.toByteArray()
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), PORT))
            val buf = ByteArray(1024); val pk = DatagramPacket(buf, buf.size)
            s.receive(pk); String(pk.data, 0, pk.length)
        }
    } catch (e: SocketTimeoutException) { null } catch (e: Exception) { null }

    /** Sends the wake-up packet (3 times) and then watches for up to [waitMs] until the console says it is on. Returns null on success, else a short reason. */
    suspend fun wake(host: String, credential: String, waitMs: Long = 25_000L): String? = withContext(Dispatchers.IO) {
        val cred = credential.trim()
        if (host.isBlank()) return@withContext "no-host"
        if (cred.isEmpty()) return@withContext "no-credential"
        val msg = "WAKEUP * HTTP/1.1\nclient-type:a\nauth-type:C\nuser-credential:$cred\ndevice-discovery-protocol-version:$VERSION\n"
        try {
            DatagramSocket().use { s ->
                val data = msg.toByteArray(); val ip = InetAddress.getByName(host)
                repeat(3) { s.send(DatagramPacket(data, data.size, ip, PORT)); delay(300) }
            }
        } catch (e: Exception) { return@withContext "send-failed: ${e.javaClass.simpleName}" }
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            delay(2000)
            if (probe(host, 800) == Power.ON) return@withContext null
        }
        "no-response"
    }

    fun wakeMessage(err: String?): String = when {
        err == null -> tr("The PS4 woke up.", "استيقظ الـPS4.")
        err == "no-credential" -> tr("Enter the PS4's wake key in this PS4's settings first.", "أدخل مفتاح التشغيل الخاص بالـPS4 في إعداداته أولاً.")
        err == "no-host" -> tr("This PS4 has no IP address.", "لا يوجد عنوان IP لهذا الـPS4.")
        err.startsWith("send-failed") -> tr("Could not send the wake packet ($err).", "تعذّر إرسال حزمة التشغيل ($err).")
        else -> tr("Wake packet sent but the PS4 did not turn on. Check the key, and on the PS4: Settings > Power Save Settings > Set Features Available in Rest Mode > turn on \"Stay Connected to the Internet\" and \"Enable Turning on PS4 from Network\".",
            "أُرسلت حزمة التشغيل لكن الـPS4 لم يعمل. تحقق من المفتاح، وفي الـPS4: الإعدادات > إعدادات توفير الطاقة > تحديد الميزات المتاحة في وضع الراحة > فعّل «البقاء متصلاً بالإنترنت» و«تمكين تشغيل PS4 من الشبكة».")
    }
}
