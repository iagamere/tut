package com.abdo.ps4monitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * Finds a PS4 on the local network by looking at the two ports this app uses (all taken from the app's own code):
 *  - 6701: PS4 server (HTTP). Identified with GET /v2/info (custom server) or GET /get_download_state (original).
 *  - 9090: GoldHEN's payload loader (BinLoader), see [ServerLoader].
 * The phone's own /24 networks (Wi-Fi and hotspot) are scanned; nothing is sent except short connection attempts and two GET requests.
 */
object Discovery {
    class Found(val host: String, val server: Boolean, val serverKind: String, val loader: Boolean) {
        /** Strong evidence: PS4 server answered, or GoldHEN's loader port is open. */
        val likely get() = server || loader
        val ports get() = buildList { if (server) add("6701"); if (loader) add("9090")}.joinToString(" • ")
    }

    /** Phone's own site-local IPv4 addresses (Wi-Fi, hotspot, USB/ethernet), cellular interfaces left out. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && listOf("rmnet", "ccmni", "pdp", "tun", "ppp", "dummy").none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun open(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** null = not an PS4 server; otherwise a short description. */
    private fun identifyServer(host: String): String? {
        fun get(path: String): Pair<Int, String>? = try {
            val c = URL("http://$host:${Ps4Server.PORT}$path").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 1500
            try { val code = c.responseCode; code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("") }
            finally { c.disconnect() }
        } catch (e: Exception) { null }
        val v2 = get("/v2/info")
        if (v2 != null) {
            if (v2.first == 401) return "custom (token required)"
            if (v2.first == 200 && v2.second.contains("\"api\"")) return "custom"
        }
        val legacy = get("/get_download_state")
        if (legacy != null && legacy.first == 200 && legacy.second.trimStart().startsWith("[")) return "original"
        return null
    }

    private fun checkHost(host: String): Found? {
        val s = open(host, Ps4Server.PORT, 700); val l = open(host, ServerLoader.PORT, 700)
        if (!s && !l) return null
        val kind = if (s) identifyServer(host) else null
        return Found(host, kind != null, kind.orEmpty(), l)
    }

    /**
     * Scans every /24 of the phone. [onProgress] gets (done, total); [onFound] is called for each host that has at least one of the ports open.
     * Returns the number of addresses scanned (0 = the phone has no local network address: Wi-Fi off?).
     */
    suspend fun scan(onProgress: (Int, Int) -> Unit, onFound: (Found) -> Unit): Int = withContext(Dispatchers.IO) {
        val mine = localAddresses()
        val targets = mine.flatMap { a ->
            val p = a.substringBeforeLast('.')
            (1..254).map { "$p.$it" }
        }.distinct().filter { it !in mine }
        if (targets.isEmpty()) return@withContext 0
        val gate = Semaphore(48); var done = 0
        coroutineScope {
            targets.map { h -> async {
                gate.withPermit {
                    val f = checkHost(h)
                    if (f != null) onFound(f)
                    synchronized(this@Discovery) { done++; onProgress(done, targets.size) }
                }
            } }.awaitAll()
        }
        targets.size
    }
}
