package com.abdo.ps4monitor
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends the server payload (an .elf) to GoldHEN's BinLoader on the PS4: TCP port 9090, write the whole file, close.
 * The file is chosen once and kept inside the app, so later sending is one tap (and the monitor can re-send it by itself
 * when the server stops answering).
 */
object ServerLoader {
    const val PORT = 9090
    fun elfFile(ctx: Context) = File(ctx.filesDir, "server.elf")

    /** Copies the chosen file into the app. Returns an error message, or null when it is saved. */
    fun importElf(ctx: Context, uri: Uri): String? {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Could not read the file."
            if (bytes.size < 1024 || bytes.size > 64 * 1024 * 1024) return "This does not look like a PS4 payload (unexpected size)."
            val elf = bytes[0].toInt() == 0x7F && bytes[1].toInt() == 0x45 && bytes[2].toInt() == 0x4C && bytes[3].toInt() == 0x46
            if (!elf) return "This is not an ELF file."
            elfFile(ctx).writeBytes(bytes)
            return null
        } catch (e: Exception) { return Ps4Api.friendly(e) }
    }

    /** Null = sent. Needs "Enable PayLoader Server" in GoldHEN's settings. */
    fun send(host: String, file: File, onProgress: (Int) -> Unit): String? {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, PORT), 5000)
                s.soTimeout = 15000
                val out = s.getOutputStream()
                val total = maxOf(1L, file.length()); var sent = 0L
                file.inputStream().use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sent += n
                        onProgress((sent * 100 / total).toInt())
                    }
                }
                out.flush()
                try { s.shutdownOutput() } catch (e: Exception) { }
            }
            return null
        } catch (e: java.net.SocketTimeoutException) { return "The PS4 did not answer on port $PORT (GoldHEN payload loader) in time."
        } catch (e: java.net.ConnectException) { return "Cannot connect to $host:$PORT (GoldHEN payload loader)."
        } catch (e: java.net.NoRouteToHostException) { return "No route to $host:$PORT (GoldHEN payload loader). Is the PS4 on and on this Wi-Fi?"
        } catch (e: Exception) { return "Sending to $host:$PORT failed: ${e.javaClass.simpleName}" }
    }

    /** Which of the PS4's known ports answer right now (for a clear error message). */
    fun portReport(host: String): String {
        fun open(p: Int) = try { Socket().use { it.connect(InetSocketAddress(host, p), 1500); true } } catch (e: Exception) { false }
        val l = open(PORT); val s = open(Ps4Server.PORT)
        fun f(b: Boolean) = if (b) "open" else "closed"
        return "$host  →  9090 (GoldHEN loader): ${f(l)}  •  6701 (server): ${f(s)}" +
            if (!l && !s) "\nNothing answers: the PS4 is off / in rest mode, or its IP changed (use Add PS4 → Find my PS4)." else ""
    }
}
