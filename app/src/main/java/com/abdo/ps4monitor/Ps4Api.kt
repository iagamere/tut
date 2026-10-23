package com.abdo.ps4monitor
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.text.SimpleDateFormat
import java.util.Locale

class Ps4Error(msg: String) : IOException(msg)

/**
 * Everything the app asks of the PS4 goes through the PS4 server (port 6701), which is sent once from GoldHEN's payload loader (port 9090).
 * Nothing else has to be installed or running on the console.
 */
object Ps4Api {
    sealed class Submit {
        object Accepted : Submit()
        data class Rejected(val message: String) : Submit()
        data class Unreachable(val message: String) : Submit()
    }
    class OpResult(val ok: Boolean, val message: String)

    private const val NEED_SERVER = "The PS4 server is not running. Open Home and send the server to the PS4 (GoldHEN must be running)."
    private const val NO_FILE_ACCESS = "The PS4 server cannot reach this folder. Turn on file access in Settings > PS4 server."

    fun friendly(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "The PS4 did not answer in time."
        is ConnectException, is NoRouteToHostException -> NEED_SERVER
        is UnknownHostException -> "PS4 address not found."
        is Ps4Error -> e.message ?: "PS4 error"
        else -> "PS4 connection problem."
    }

    /** Links are only ever sent to the PS4 server; see [Ps4Server.add]. */
    fun submit(ps4: Ps4, url: String, destPath: String): Submit = Submit.Unreachable(NEED_SERVER)

    private fun usable(ps4: Ps4, path: String, write: Boolean = true) = Ps4Server.fsUsable(ps4, path, write)

    fun list(ps4: Ps4, path: String, timeoutMs: Int): List<FsEntry> {
        if (!usable(ps4, path, false)) throw Ps4Error(NO_FILE_ACCESS)
        try { return Ps4Server.fsList(ps4, path, timeoutMs) } catch (e: Ps4Server.FsUnavailable) { throw Ps4Error(NO_FILE_ACCESS) }
    }
    /** "date" is "YYYY-MM-DD HH:MM:SS" (PS4 wall clock), parsed with the phone zone so it is displayed unchanged. */
    fun parseDate(t: String): Long = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(t)?.time ?: 0L }.getOrDefault(0L)

    fun remove(ps4: Ps4, paths: List<String>, readMs: Int = 120_000): OpResult {
        if (paths.isEmpty() || !paths.all { usable(ps4, it) }) return OpResult(false, NO_FILE_ACCESS)
        try {
            for (p in paths) { val r = Ps4Server.fsOp(ps4, "delete", JSONObject().put("path", p)); if (!r.ok && !r.message.startsWith("No such")) return r }
            return OpResult(true, "")
        } catch (e: Ps4Server.FsUnavailable) { return OpResult(false, NO_FILE_ACCESS) }
    }
    fun rename(ps4: Ps4, from: String, to: String): OpResult {
        if (!usable(ps4, from) || !usable(ps4, to)) return OpResult(false, NO_FILE_ACCESS)
        return try { Ps4Server.fsOp(ps4, "rename", JSONObject().put("from", from).put("to", to)) } catch (e: Ps4Server.FsUnavailable) { OpResult(false, NO_FILE_ACCESS) }
    }
    fun createFolder(ps4: Ps4, path: String): OpResult {
        if (!usable(ps4, path)) return OpResult(false, NO_FILE_ACCESS)
        return try { Ps4Server.fsOp(ps4, "mkdir", JSONObject().put("path", path)) } catch (e: Ps4Server.FsUnavailable) { OpResult(false, NO_FILE_ACCESS) }
    }
    private fun viaServer(ps4: Ps4, op: String, paths: List<String>, dest: String, readOnlySource: Boolean): OpResult {
        if (paths.isEmpty() || !paths.all { usable(ps4, it, !readOnlySource) } || !usable(ps4, dest)) return OpResult(false, NO_FILE_ACCESS)
        if (!Ps4Server.installSupported(ps4)) return OpResult(false, "Send the latest server to the PS4 first (Home > server).")
        try {
            for (p in paths) {
                val r = Ps4Server.fsOp(ps4, op, JSONObject().put("from", p).put("to", joinPath(dest, p.substringAfterLast('/'))), 3_600_000)
                if (!r.ok) return r
            }
            return OpResult(true, "")
        } catch (e: Ps4Server.FsUnavailable) { return OpResult(false, NO_FILE_ACCESS) }
    }
    fun move(ps4: Ps4, paths: List<String>, dest: String): OpResult = viaServer(ps4, "rename", paths, dest, false)
    fun copy(ps4: Ps4, paths: List<String>, dest: String): OpResult = viaServer(ps4, "copy", paths, dest, true)
    /** The server has no archive support. */
    fun extract(ps4: Ps4, item: String, destination: String, folderName: String) = OpResult(false, "Extracting archives is not supported by the PS4 server.")
    /** The server has no text-write call. */
    fun edit(ps4: Ps4, path: String, content: String) = OpResult(false, "Editing files is not supported by the PS4 server.")

    /** Size in bytes; 0 when the file does not exist. */
    fun fileSize(ps4: Ps4, path: String, timeoutMs: Int): Long {
        if (!usable(ps4, path, false)) throw Ps4Error(NO_FILE_ACCESS)
        try { return Ps4Server.fsSizeSafe(ps4, path, timeoutMs) } catch (e: Ps4Server.FsUnavailable) { throw Ps4Error(NO_FILE_ACCESS) }
    }
    fun readRange(ps4: Ps4, path: String, offset: Long, length: Int, timeoutMs: Int): ByteArray {
        if (!usable(ps4, path, false)) throw Ps4Error(NO_FILE_ACCESS)
        try { return Ps4Server.fsRead(ps4, path, offset, length, timeoutMs) } catch (e: Ps4Server.FsUnavailable) { throw Ps4Error(NO_FILE_ACCESS) }
    }
}
