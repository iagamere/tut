package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * One download known to PS4 server. `path` is the FINAL file path; the data is written to "<path>.tmp".
 * Fields after `ts` exist only with the custom server (/v2 API): [id] is its handle for pause/resume/delete.
 */
class SrvEntry(val path: String, val bytes: Long, val size: Long, val state: Int, val ts: Long,
               val id: Int = 0, val speed: Double = 0.0, val conns: Int = 0, val attempts: Int = 0, val error: String = "", val v2: Boolean = false,
               val fatal: Boolean = false, val priority: Int = 0, val position: Int = 0) {
    val pending get() = state == 0
    val active get() = state == 1 || state == 2 || state == 7   // DOWNLOADING, RESUMED, STOPPING (v2)
    val failed get() = state == 3
    val done get() = state == 4
    val paused get() = state == 5                               // v2 only
    val needsLink get() = state == 6                            // v2 only: expired link, send it again to the same file
}

/**
 * PS4 server (ps4-ezremote-server): the component that really downloads. Listens on 0.0.0.0:6701. Two flavours:
 *  - the ORIGINAL: GET /get_download_state -> [{path, bytes_transfered, file_size, state, timestamp}] (state 0..4), GET /stop kills the whole
 *    process. One download at a time, no per-download cancel/pause/remove (those need stop + edit of bg_download_history.json).
 *  - the CUSTOM build (ps4-ezremote-server-abdo): the same legacy routes plus /v2: GET /v2/info, GET /v2/downloads, POST /v2/pause,
 *    /v2/resume, /v2/delete {id,...}. Detected with GET /v2/info (an original server answers 404), so both keep working.
 * `file_size` of the original is what PS4 server measured (garbage when that failed), so it is range-checked.
 */
object Ps4Server {
    const val PORT = 6701
    class Reply(val code: Int, val body: String)
    private class V2Info(val ok: Boolean, val at: Long, val maxAttempts: Int, val fsRoots: List<String> = emptyList(), val fsApi: Int = 0, val roRoots: List<String> = emptyList())
    private val v2cache = ConcurrentHashMap<String, V2Info>()
    private val locked = ConcurrentHashMap<String, Boolean>()
    /** The server answered 401: it has a token and this app has none (or a wrong one). */
    fun isLocked(ps4: Ps4): Boolean = locked[ps4.id] == true
    private fun open(ps4: Ps4, method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL("http://${ps4.host}:$PORT$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = method
        if (ps4.token.isNotEmpty()) c.setRequestProperty("X-Token", ps4.token)
        return c
    }
    private fun rootsOf(st: JSONObject?, key: String = "fs_roots"): List<String> = st?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("/") } } ?: emptyList()

    private fun call(ps4: Ps4, method: String, path: String, body: String?, timeoutMs: Int): Reply {
        val c = open(ps4, method, path, timeoutMs)
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            return Reply(code, text)
        } finally { if (method != "GET") c.disconnect() }      // a fully read GET keeps its connection for the next request (a new TCP connection per call made every screen slow)
    }
    private fun get(ps4: Ps4, path: String, timeoutMs: Int): String {
        val r = call(ps4, "GET", path, null, timeoutMs)
        if (r.code !in 200..299) throw Ps4Error("PS4 server answered HTTP ${r.code}.")
        return r.body
    }

    /** True when the server is the custom build with the /v2 API. Cached; throws (like [list]) when the server is not reachable. */
    fun isV2(ps4: Ps4, timeoutMs: Int): Boolean {
        val now = System.currentTimeMillis(); val c = v2cache[ps4.id]
        if (c != null && now - c.at < (if (c.ok) 120_000L else 30_000L)) return c.ok
        val r = call(ps4, "GET", "/v2/info", null, timeoutMs)
        if (r.code == 401) {
            if (locked.put(ps4.id, true) != true) DownloadMonitor.d("PS4 server asks for a token (X-Token): enter it in this PS4's settings in the app.")
            v2cache[ps4.id] = V2Info(false, now, 5)
            return false
        }
        locked.remove(ps4.id)
        val info = if (r.code == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
        val ok = info != null && info.optInt("api") >= 2
        val st = info?.optJSONObject("settings")
        val maxAtt = st?.optInt("max_attempts", 5) ?: 5
        val fsApi = info?.optInt("fs_api") ?: 0
        var roots = rootsOf(st)
        // file access through the server is switched on once (the user can turn it off in the Server screen); the server keeps it disabled until then
        if (ok && fsApi >= 1 && roots.isEmpty() && Store.sp.getBoolean("fs_auto", true)) {
            enableFs(ps4, true)?.let { roots = it; DownloadMonitor.d("File access through the server enabled for: " + it.joinToString()) }
        }
        var ro = rootsOf(st, "fs_ro_roots")
        // read-only folders (captures, installed games): pushed once; the user's extra folders are added to the defaults
        if (ok && fsApi >= 2 && ro.isEmpty() && Store.sp.getBoolean("ro_auto", true)) {
            pushRo(ps4)?.let { ro = it; DownloadMonitor.d("Read-only folders enabled on the server: " + it.joinToString()) }
        }
        v2cache[ps4.id] = V2Info(ok, now, maxAtt, roots, fsApi, ro)
        return ok
    }
    /** What the last check found; never touches the network. */
    fun isV2Known(ps4: Ps4): Boolean = v2cache[ps4.id]?.ok == true
    fun maxAttempts(ps4: Ps4): Int = v2cache[ps4.id]?.maxAttempts ?: 5

    fun list(ps4: Ps4, timeoutMs: Int): List<SrvEntry> {
        if (isV2(ps4, timeoutMs)) {
            val r = call(ps4, "GET", "/v2/downloads", null, timeoutMs)
            if (r.code == 401) { locked[ps4.id] = true; v2cache.remove(ps4.id) }   // token changed under us: re-detect
            else if (r.code == 404) v2cache.remove(ps4.id)                       // replaced by an original server: use the old API below
            else if (r.code in 200..299) return parseV2(r.body)
            else throw Ps4Error("PS4 server answered HTTP ${r.code}.")
        }
        return parseLegacy(get(ps4, "/get_download_state", timeoutMs))
    }
    private fun parseLegacy(text: String): List<SrvEntry> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            SrvEntry(o.optString("path"), o.optLong("bytes_transfered"), o.optLong("file_size"), o.optInt("state", -1), o.optLong("timestamp")) }
    }
    private fun parseV2(text: String): List<SrvEntry> {
        val a = JSONObject(text).optJSONArray("downloads") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            SrvEntry(o.optString("path"), o.optLong("bytes"), o.optLong("size"), o.optInt("state", -1), o.optLong("timestamp"),
                id = o.optInt("id"), speed = o.optDouble("speed", 0.0), conns = o.optInt("connections"), attempts = o.optInt("attempts"),
                error = o.optString("error"), v2 = true, fatal = o.optBoolean("final"), priority = o.optInt("priority"), position = o.optInt("position")) }
    }
    fun up(ps4: Ps4): Boolean = try { list(ps4, 3000); true } catch (e: Exception) { false }

    /** Terminates PS4 server (original: the only way to cancel anything). True when it no longer answers afterwards. */
    fun stop(ps4: Ps4): Boolean {
        try { get(ps4, "/stop", 3000) } catch (e: Exception) { }
        Thread.sleep(2000)
        return !up(ps4)
    }
    fun newest(l: List<SrvEntry>, path: String): SrvEntry? = l.filter { it.path == path }.maxByOrNull { it.ts }
    fun sane(size: Long) = size in (1L shl 16)..(1L shl 42)

    // ---------------- status / settings / log of the custom server ----------------
    class ServerInfo(val version: String, val custom: Boolean, val maxParallel: Int, val maxConns: Int, val auto: Boolean, val fixedConns: Int,
                     val fsRoots: List<String> = emptyList(), val fsApi: Int = 0, val tokenSet: Boolean = false, val locked: Boolean = false,
                     val roRoots: List<String> = emptyList(), val trophySync: Boolean = false)
    /** null = not reachable. An original server answers 404 to /v2/info. */
    fun info(ps4: Ps4, timeoutMs: Int = 3000): ServerInfo? = try {
        val r = call(ps4, "GET", "/v2/info", null, timeoutMs)
        val j = if (r.code == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
        if (r.code == 401) ServerInfo("", true, 2, 8, true, 4, locked = true)
        else if (j != null && j.optInt("api") >= 2) {
            val st = j.optJSONObject("settings")
            ServerInfo(j.optString("version"), true, st?.optInt("max_parallel", 2) ?: 2, st?.optInt("max_conns_per_download", 8) ?: 8,
                st?.optBoolean("auto_conns", true) ?: true, st?.optInt("fixed_conns", 4) ?: 4,
                rootsOf(st), j.optInt("fs_api"), st?.optBoolean("token_set", false) ?: false, roRoots = rootsOf(st, "fs_ro_roots"), trophySync = j.optInt("trophy_sync") > 0)
        } else if (r.code == 404) ServerInfo("original", false, 1, 1, false, 1)
        else null
    } catch (e: Exception) { null }
    /** State of the server's trophy sync (POST /v2/trophy/sync starts it, GET /v2/trophy/status reads it). */
    class TrophyStatus(val running: Boolean, val runs: Int, val total: Int, val done: Int, val exported: Int, val failed: Int, val log: List<String>)
    private fun trophyStatusOf(r: Reply): TrophyStatus? {
        val j = if (r.code == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
        if (j == null) return null
        val a = j.optJSONArray("log")
        val lines = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        return TrophyStatus(j.optBoolean("running"), j.optInt("runs"), j.optInt("total"), j.optInt("done"), j.optInt("exported"), j.optInt("failed"), lines)
    }
    fun trophySync(ps4: Ps4, force: Boolean): TrophyStatus? = try { trophyStatusOf(call(ps4, "POST", "/v2/trophy/sync", if (force) "{\"force\":true}" else "{}", 8000)) } catch (e: Exception) { null }
    fun trophyStatus(ps4: Ps4): TrophyStatus? = try { trophyStatusOf(call(ps4, "GET", "/v2/trophy/status", null, 5000)) } catch (e: Exception) { null }
    /** Forget what was detected (after the server was replaced). */
    fun forget(ps4: Ps4) { v2cache.remove(ps4.id) }
    fun updateSettings(ps4: Ps4, json: String): Ctl = control(ps4, "/v2/settings", json)
    /** Last lines of the server's log (custom server only). */
    fun log(ps4: Ps4, lines: Int): String = get(ps4, "/v2/log?lines=$lines", 5000)
    /** The server's log FILE on the PS4 (readable even when the server is not running), last 12 KB. */
    fun logFile(ps4: Ps4): String {
        val path = "/data/ezremote-client/dlserver.log"
        val size = Ps4Api.fileSize(ps4, path, 6000)
        if (size <= 0) throw Ps4Error("dlserver.log was not found on the PS4.")
        val len = minOf(size, 12_288L).toInt()
        return String(Ps4Api.readRange(ps4, path, size - len, len, 8000), Charsets.UTF_8)
    }

    // ---------------- /v2 control (custom server only) ----------------
    /** [legacy] = the server has no /v2 API (an original server): the caller falls back to the old stop-and-edit method. */
    class Ctl(val ok: Boolean, val message: String, val legacy: Boolean = false)
    private fun control(ps4: Ps4, path: String, body: String): Ctl = try {
        val r = call(ps4, "POST", path, body, 8000)
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        when {
            r.code in 200..299 && j?.optBoolean("ok", true) != false -> Ctl(true, "")
            r.code == 401 -> Ctl(false, "The server asks for a token. Enter it in this PS4's settings in the app.")
            j == null && r.code == 404 -> Ctl(false, "", legacy = true)
            else -> Ctl(false, j?.optString("error").orEmpty().ifBlank { "HTTP ${r.code}" })
        }
    } catch (e: Exception) { Ctl(false, Ps4Api.friendly(e)) }
    /**
     * Queues a download directly on the custom server (POST /v2/add), WITHOUT PS4 server's web server (port 8080). The PS4 suspends PS4 server
     * Client while a game runs, and its web server with it; the server is a payload and keeps answering. Same fields PS4 server would send:
     * type 7 (file host), host = scheme://host[:port], src_path = path + query, dest_path = the final file path.
     * Sending the same dest_path again replaces the link and continues (that is how an expired link is refreshed).
     * Unreachable (not Rejected) is returned for anything that is not a plain http(s) link or when the server does not answer, so the caller can
     * still try the old route.
     */
    fun add(ps4: Ps4, url: String, destPath: String): Ps4Api.Submit {
        val m = Regex("^(https?://[^/?#@]+)([^#]*)$", RegexOption.IGNORE_CASE).matchEntire(url.trim())
            ?: return Ps4Api.Submit.Unreachable("This link cannot be sent directly to the server.")
        var path = m.groupValues[2]
        if (path.isEmpty()) path = "/" else if (path.startsWith("?")) path = "/$path"
        path = path.replace(" ", "%20")
        val body = JSONObject().put("type", 7).put("url", m.groupValues[1]).put("src_path", path).put("dest_path", destPath).put("size", 0).toString()
        return try {
            val r = call(ps4, "POST", "/v2/add", body, 10_000)
            val j = runCatching { JSONObject(r.body) }.getOrNull()
            when {
                r.code in 200..299 && j?.optBoolean("ok", true) != false -> Ps4Api.Submit.Accepted
                j != null && j.has("error") -> Ps4Api.Submit.Rejected("PS4 server rejected the request: " + j.optString("error"))
                else -> Ps4Api.Submit.Rejected("PS4 server answered HTTP ${r.code}.")
            }
        } catch (e: Exception) { Ps4Api.Submit.Unreachable(Ps4Api.friendly(e)) }
    }
    // ---------------- queue order (custom server) ----------------
    fun setPriority(ps4: Ps4, id: Int, priority: Int): Ctl = control(ps4, "/v2/priority", "{\"id\":$id,\"priority\":$priority}")
    /** to: top | bottom | up | down */
    fun move(ps4: Ps4, id: Int, to: String): Ctl = control(ps4, "/v2/move", "{\"id\":$id,\"to\":\"$to\"}")
    /** Top of the queue, priority above all others, resumes it if stopped; with preemption another download steps aside. */
    fun startNow(ps4: Ps4, id: Int): Ctl = control(ps4, "/v2/start_now", "{\"id\":$id}")

    // ---------------- file access through the server (/v2/fs/*) ----------------
    // The PS4 suspends PS4 server and its web server (port 8080) while a game runs; this server keeps answering. Ps4Api's file functions
    // try the server first for paths inside the folders the server allows, and fall back to the web server when the server cannot do it.
    class FsUnavailable(msg: String) : IOException(msg)
    private val fsFailAt = ConcurrentHashMap<String, Long>()
    private const val FS_ROOTS = "[\"/data\",\"/mnt\"]"
    private fun enableFs(ps4: Ps4, on: Boolean): List<String>? = try {
        val r = call(ps4, "POST", "/v2/settings", "{\"fs_roots\":" + (if (on) FS_ROOTS else "[]") + "}", 4000)
        if (r.code in 200..299) rootsOf(JSONObject(r.body).optJSONObject("settings")) else null
    } catch (e: Exception) { null }
    /** Turns file access through the server on or off (and remembers the choice). Null = done, otherwise an error text. */
    fun setFsAccess(ps4: Ps4, on: Boolean): String? {
        Store.sp.edit().putBoolean("fs_auto", on).apply()
        val roots = enableFs(ps4, on)
        forget(ps4)
        return if (roots == null) "Could not change the setting on the server." else null
    }
    /** Sets (or clears, with "") the token the server requires. The app must send the NEW value from then on: the caller saves it. */
    fun setServerToken(ps4: Ps4, token: String): Ctl = control(ps4, "/v2/settings", JSONObject().put("token", token).toString())

    /** write = false also accepts the server's read-only folders (list / stat / read). */
    fun fsUsable(ps4: Ps4, path: String, write: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        if (now - (fsFailAt[ps4.id] ?: 0L) < 10_000L) return false
        val ok = try { isV2(ps4, 2500) } catch (e: Exception) { fsFailAt[ps4.id] = now; false }
        if (!ok) return false
        val c = v2cache[ps4.id]
        val roots = c?.fsRoots.orEmpty() + (if (write) emptyList<String>() else c?.roRoots.orEmpty())
        return roots.any { path == it || path.startsWith("$it/") }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** Definitive failures (missing file, name taken...) become Ps4Error; everything that means "ask the web server instead" becomes FsUnavailable. */
    private fun fsJson(ps4: Ps4, r: Reply): JSONObject {
        if (r.code == 401 || r.code == 403) { if (r.code == 403) v2cache.remove(ps4.id); throw FsUnavailable("HTTP ${r.code}") }
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: throw FsUnavailable("Unexpected reply (HTTP ${r.code})")
        if (r.code !in 200..299 || !j.optBoolean("ok", true)) throw Ps4Error(j.optString("error").ifBlank { "PS4 server answered HTTP ${r.code}." })
        return j
    }
    private fun fsCall(ps4: Ps4, method: String, path: String, body: String?, timeoutMs: Int): JSONObject {
        val r = try { call(ps4, method, path, body, timeoutMs) } catch (e: IOException) { fsFailAt[ps4.id] = System.currentTimeMillis(); throw FsUnavailable(Ps4Api.friendly(e)) }
        return fsJson(ps4, r)
    }
    fun fsList(ps4: Ps4, path: String, timeoutMs: Int): List<FsEntry> {
        val a = fsCall(ps4, "GET", "/v2/fs/list?path=" + enc(path), null, timeoutMs).optJSONArray("entries") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
            val dt = Ps4Api.parseDate(e.optString("date"))
            FsEntry(e.optString("name"), e.optLong("size"), if (dt > 0) dt else e.optLong("mtime") * 1000, e.optBoolean("dir")) }
    }
    /** Size in bytes; 0 when the file does not exist (same convention as the web query). */
    fun fsSize(ps4: Ps4, path: String, timeoutMs: Int): Long {
        val j = fsCall(ps4, "GET", "/v2/fs/stat?path=" + enc(path), null, timeoutMs)
        return if (j.optBoolean("exists", false)) j.optLong("size") else 0L
    }
    fun fsSizeSafe(ps4: Ps4, path: String, timeoutMs: Int): Long =
        try { fsSize(ps4, path, timeoutMs) } catch (e: Ps4Error) { if ((e.message ?: "").let { it.startsWith("Parent folder") || it.startsWith("No such") }) 0L else throw e }
    fun fsRead(ps4: Ps4, path: String, offset: Long, length: Int, timeoutMs: Int): ByteArray {
        val out = ByteArrayOutputStream(); var off = offset; var left = length
        while (left > 0) {
            val n = minOf(left, 4 shl 20)
            val c = open(ps4, "GET", "/v2/fs/read?path=" + enc(path) + "&offset=$off&length=$n", timeoutMs)
            val chunk: ByteArray = try {
                val code = try { c.responseCode } catch (e: IOException) { fsFailAt[ps4.id] = System.currentTimeMillis(); throw FsUnavailable(Ps4Api.friendly(e)) }
                if (code == 200) c.inputStream.use { it.readBytes() }
                else fsJson(ps4, Reply(code, runCatching { c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault(""))).let { ByteArray(0) }
            } finally { /* no disconnect: the connection is reused */ }
            if (chunk.isEmpty()) break
            out.write(chunk); off += chunk.size; left -= chunk.size
        }
        return out.toByteArray()
    }
    /** op: delete {path} | rename {from,to} | mkdir {path}. */
    fun fsOp(ps4: Ps4, op: String, body: JSONObject, timeoutMs: Int = 60_000): Ps4Api.OpResult = try {
        fsCall(ps4, "POST", "/v2/fs/$op", body.toString(), timeoutMs); Ps4Api.OpResult(true, "")
    } catch (e: Ps4Error) { Ps4Api.OpResult(false, e.message ?: "PS4 server refused the request.") }

    // ---------------- deleting captures ----------------
    /** The only folders where this app deletes captures. Added to the server's WRITABLE folders (fs_roots) on demand, never by default. */
    val CAPTURE_DIRS = listOf("/user/av_contents/photo", "/user/av_contents/video")
    private fun isCapturePath(p: String) = !p.contains("..") && CAPTURE_DIRS.any { p.startsWith("$it/") }

    /** The server refuses deletes in its read-only folders ("Path is outside the writable folders (fs_roots)"): adds the capture folders to fs_roots, keeping the others. Null = done, otherwise an error text. */
    fun allowCaptureDelete(ps4: Ps4): String? = try {
        if (!isV2(ps4, 4000)) "The server is not running (or is not our server)."
        else {
            val cur = v2cache[ps4.id]?.fsRoots.orEmpty()
            if (CAPTURE_DIRS.all { it in cur }) null
            else {
                val arr = JSONArray(); (cur + CAPTURE_DIRS).distinct().forEach { arr.put(it) }
                val r = call(ps4, "POST", "/v2/settings", JSONObject().put("fs_roots", arr).toString(), 5000)
                forget(ps4)
                if (r.code !in 200..299) "The server refused to make the capture folders writable (HTTP ${r.code})."
                else {
                    val now = rootsOf(runCatching { JSONObject(r.body).optJSONObject("settings") }.getOrNull())
                    if (CAPTURE_DIRS.all { it in now }) null else "The server did not accept the capture folders as writable. It now allows: " + now.joinToString().ifBlank { "nothing" }
                }
            }
        }
    } catch (e: Exception) { Ps4Api.friendly(e) }

    /** Deletes one capture file. Only paths under the two capture folders are accepted. */
    fun deleteCapture(ps4: Ps4, path: String): Ps4Api.OpResult {
        if (!isCapturePath(path)) return Ps4Api.OpResult(false, "Refused: not a capture path.")
        return try { fsOp(ps4, "delete", JSONObject().put("path", path)) }
        catch (e: Exception) { Ps4Api.OpResult(false, if (e is FsUnavailable) (e.message ?: "The server is not reachable.") else Ps4Api.friendly(e)) }
    }

    // ---------------- deleting from the storage browser / games (DeletePolicy decides WHAT; this only opens the door briefly) ----------------
    /** Master switch in the Storage tab. Off by default: with it off the app deletes nothing from the Storage / Games screens. */
    fun deleteAllowed(): Boolean = Store.sp.getBoolean("del_ok", false)
    fun setDeleteAllowed(on: Boolean) { Store.sp.edit().putBoolean("del_ok", on).apply() }

    /** [previous] = the writable folders before; [changed] = this call added something that [closeWritable] must take away again. */
    class Window(val previous: List<String>, val changed: Boolean)
    private fun covered(roots: List<String>, p: String) = roots.any { p == it || p.startsWith("$it/") }
    /** Makes sure every folder of [roots] is writable on the server (adds the missing ones to fs_roots, keeping the others). Window or an error text. */
    fun openWritable(ps4: Ps4, roots: List<String>): Pair<Window?, String?> = try {
        forget(ps4)
        if (!isV2(ps4, 4000)) null to "The server is not running (or is not our server)."
        else {
            val cur = v2cache[ps4.id]?.fsRoots.orEmpty()
            val missing = roots.filter { !covered(cur, it) }
            if (missing.isEmpty()) Window(cur, false) to null
            else {
                val arr = JSONArray(); (cur + missing).distinct().forEach { arr.put(it) }
                val r = call(ps4, "POST", "/v2/settings", JSONObject().put("fs_roots", arr).toString(), 5000)
                forget(ps4)
                if (r.code !in 200..299) null to "The server refused to make the folder writable (HTTP ${r.code})."
                else {
                    val now = rootsOf(runCatching { JSONObject(r.body).optJSONObject("settings") }.getOrNull())
                    if (missing.any { !covered(now, it) }) null to "The server did not accept the folder as writable."
                    else Window(cur, true) to null
                }
            }
        }
    } catch (e: Exception) { null to Ps4Api.friendly(e) }
    /** Puts fs_roots back as it was before [openWritable]. Best effort. */
    fun closeWritable(ps4: Ps4, w: Window) {
        if (!w.changed) return
        try {
            val arr = JSONArray(); w.previous.forEach { arr.put(it) }
            call(ps4, "POST", "/v2/settings", JSONObject().put("fs_roots", arr).toString(), 5000)
        } catch (e: Exception) { }
        forget(ps4)
    }

    // ---------------- read-only folders, space, system info (server abdo10, fs_api 2) ----------------
    val RO_DEFAULT = listOf("/user/trophy", "/system_data/priv/trophy", "/system_data/priv/cache/profile", "/user/av_contents", "/user/app", "/user/appmeta", "/system_data/priv/appmeta", "/user/home", "/system_data/priv/home", "/user/theme", "/user/themes", "/system_data/priv/theme", "/system_data/theme", "/system_ex/theme", "/user/system/theme")
    /** Extra read-only folders typed by the user (one per line in the preferences). */
    fun roExtra(): List<String> = (Store.sp.getString("ro_extra", "") ?: "").lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("/") && it.length > 1 && !it.contains("..") }.distinct()
    fun roWanted(): List<String> = (RO_DEFAULT + roExtra()).distinct()
    private fun pushRo(ps4: Ps4): List<String>? = try {
        val arr = JSONArray(); roWanted().forEach { arr.put(it) }
        val r = call(ps4, "POST", "/v2/settings", JSONObject().put("fs_ro_roots", arr).toString(), 4000)
        if (r.code in 200..299) rootsOf(JSONObject(r.body).optJSONObject("settings"), "fs_ro_roots") else null
    } catch (e: Exception) { null }
    /** Saves the extra folders and sends the full list to the server. Null = done, otherwise an error text. */
    fun setRoExtra(ps4: Ps4, lines: String): String? {
        Store.sp.edit().putString("ro_extra", lines).putBoolean("ro_auto", true).apply()
        val roots = pushRo(ps4)
        forget(ps4)
        return if (roots == null) "Could not change the setting on the server." else null
    }
    /** Makes sure the server is the custom build with fs_api >= 2 and the read-only folders are set. Null = ready, otherwise an English reason (see Tx). */
    fun prepareInfo(ps4: Ps4): String? {
        forget(ps4)
        val i = info(ps4, 4000) ?: return "PS4 server is not running. Launch PS4 server on the PS4."
        if (i.locked) return "The server asks for a token. Enter it in this PS4's settings."
        if (!i.custom) return "This feature needs the custom server (abdo10 or newer)."
        if (i.fsApi < 2) return "The server is too old for this feature: send the abdo10 server from the Server screen."
        val ok = try { isV2(ps4, 4000) } catch (e: Exception) { false }
        if (!ok) return "PS4 server is not running. Launch PS4 server on the PS4."
        if (v2cache[ps4.id]?.roRoots.isNullOrEmpty() && v2cache[ps4.id]?.fsRoots.isNullOrEmpty()) return "File access through the server is switched off (Server screen)."
        return null
    }
    fun roRoots(ps4: Ps4): List<String> = v2cache[ps4.id]?.roRoots.orEmpty()
    fun fsRootsKnown(ps4: Ps4): List<String> = v2cache[ps4.id]?.fsRoots.orEmpty()
    /** (total, free) bytes of the volume that holds [path]. */
    fun fsSpace(ps4: Ps4, path: String, timeoutMs: Int): Pair<Long, Long> {
        val j = fsCall(ps4, "GET", "/v2/fs/space?path=" + enc(path), null, timeoutMs)
        return j.optLong("total") to j.optLong("free")
    }
    // ---------------- install through the server (abdo12, fs_api 3) ----------------
    /** True when the running server can install packages and copy files by itself (fs_api >= 3). */
    fun installSupported(ps4: Ps4): Boolean = (v2cache[ps4.id]?.fsApi ?: 0) >= 3
    class InstallStart(val taskId: Int, val titleId: String)
    class InstallProgress(val length: Long, val transferred: Long, val errorResult: Int, val done: Boolean)
    /** Starts installing a .pkg that is on the console (what Debug Settings -> Package Installer does). Throws Ps4Error with the server's reason. */
    fun installPkg(ps4: Ps4, path: String): InstallStart {
        val j = fsCall(ps4, "POST", "/v2/install", JSONObject().put("path", path).toString(), 60_000)
        return InstallStart(j.optInt("task_id", -1), j.optString("title_id"))
    }
    fun installStatus(ps4: Ps4, taskId: Int): InstallProgress {
        val j = fsCall(ps4, "GET", "/v2/install/status?task=$taskId", null, 8000)
        return InstallProgress(j.optLong("length"), j.optLong("transferred"), j.optInt("error_result"), j.optBoolean("done"))
    }
    /** What the console's kernel reports (only names that exist are returned). Null = not available. */
    fun sysinfo(ps4: Ps4): JSONObject? = try { fsCall(ps4, "GET", "/v2/sysinfo", null, 5000) } catch (e: Exception) { null }
    /** Streams a whole file into [out] in 4 MiB pieces. [size] must be the real size (from stat). */
    fun fsCopyTo(ps4: Ps4, path: String, size: Long, out: java.io.OutputStream, onProgress: (Long) -> Unit) {
        var off = 0L
        while (off < size) {
            val chunk = fsRead(ps4, path, off, 4 shl 20, 30_000)
            if (chunk.isEmpty()) throw Ps4Error("The PS4 stopped sending the file at $off of $size bytes.")
            out.write(chunk); off += chunk.size; onProgress(off)
        }
    }

    fun pause(ps4: Ps4, id: Int): Ctl = control(ps4, "/v2/pause", "{\"id\":$id}")
    fun resume(ps4: Ps4, id: Int): Ctl = control(ps4, "/v2/resume", "{\"id\":$id}")
    /** Cancels a running download without touching the others. deleteFiles: also the partial file (and the finished file of a finished one). */
    fun delete(ps4: Ps4, id: Int, deleteFiles: Boolean): Ctl = control(ps4, "/v2/delete", "{\"id\":$id,\"delete_files\":$deleteFiles}")
}

/** One element of /data/ezremote-client/bg_download_history.json (credentials are never read). */
class BgEntry(val dest: String, val size: Long, val bytes: Long, val state: Int, val attempts: Int, val ts: Long,
              val custom: Boolean = false, val pausedFlag: Boolean = false) {
    /** Original server: FAILED with failed_attempts >= 5 is never retried. The custom server retries failures by itself and marks real stops explicitly. */
    val stopped get() = if (custom) pausedFlag else state == 3 && attempts >= 5
}

/**
 * bg_download_history.json is the persistent list of PS4 server. It is read ONCE when the server starts; the running server uses its
 * memory copy and rewrites the file only on some events, so edits only take effect after the server is stopped and started again.
 * At start-up an entry resumes when state==1 or (state==3 and failed_attempts<5), continuing from the .tmp size.
 * Edits here are text-level on the target element only (64-bit ids stay byte-identical), validated and read back.
 */
object BgHistory {
    const val PATH = "/data/ezremote-client/bg_download_history.json"
    private val cache = ConcurrentHashMap<String, Pair<Long, List<BgEntry>>>()
    class Edit(val ok: Boolean, val message: String)

    private fun readText(p: Ps4): String {
        val size = Ps4Api.fileSize(p, PATH, 8000)
        if (size <= 0) throw Ps4Error("bg_download_history.json was not found on the PS4.")
        if (size > (2L shl 20)) throw Ps4Error("bg_download_history.json is unexpectedly large.")
        val t = String(Ps4Api.readRange(p, PATH, 0, size.toInt(), 10_000), Charsets.UTF_8)
        val end = t.lastIndexOf(']')
        if (end < 0) throw Ps4Error("bg_download_history.json is not a JSON list.")
        return t.substring(0, end + 1)
    }
    private fun parse(t: String): List<BgEntry> {
        val a = JSONArray(t)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            BgEntry(o.optString("dest_path"), o.optLong("file_size"), o.optLong("bytes_transfered"), o.optInt("state", -1), o.optInt("failed_attempts"), o.optLong("timestamp"),
                custom = o.has("uid"), pausedFlag = o.optBoolean("paused") || o.optBoolean("needs_link") || o.optBoolean("final")) }
    }
    fun read(p: Ps4): List<BgEntry> = parse(readText(p))
    /** At most one read every 15 s per PS4; null when the file cannot be read. */
    fun cached(p: Ps4): List<BgEntry>? {
        val c = cache[p.id]; val now = System.currentTimeMillis()
        if (c != null && now - c.first < 15_000) return c.second
        val l = try { read(p) } catch (e: Exception) { null }
        if (l != null) cache[p.id] = now to l
        return l ?: c?.second
    }
    fun entry(l: List<BgEntry>?, dest: String): BgEntry? = l?.filter { it.dest == dest }?.maxByOrNull { it.ts }

    /** [start, end) of every top-level object of the JSON array (string-aware). */
    private fun spans(t: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(); var depth = 0; var inStr = false; var esc = false; var start = -1
        for (i in t.indices) {
            val c = t[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> { depth++; if (depth == 2) start = i }
                '}' -> { if (depth == 2 && start >= 0) { out += start to (i + 1); start = -1 }; depth-- }
                '[' -> depth++
                ']' -> depth--
            }
        }
        return out
    }
    private fun destOf(raw: String, s: Pair<Int, Int>): Pair<String, Long>? = try { JSONObject(raw.substring(s.first, s.second)).let { it.optString("dest_path") to it.optLong("timestamp") } } catch (e: Exception) { null }

    private fun write(p: Ps4, out: String, expectEntries: Int): Edit {
        val check = try { parse(out) } catch (e: Exception) { return Edit(false, "The edited file would not be valid JSON; nothing was written.") }
        if (check.size != expectEntries) return Edit(false, "The edit would change the number of entries unexpectedly; nothing was written.")
        val r = Ps4Api.edit(p, PATH, out)
        cache.remove(p.id)
        return if (r.ok) Edit(true, "") else Edit(false, r.message)
    }

    /** state=3 and failed_attempts=[attempts] on the newest entry for [dest]. Only call while PS4 server is NOT running. */
    fun mark(p: Ps4, dest: String, attempts: Int): Edit = try {
        val raw = readText(p); val spans = spans(raw)
        var best = -1; var bestTs = Long.MIN_VALUE
        spans.forEachIndexed { i, s -> val d = destOf(raw, s); if (d != null && d.first == dest && d.second >= bestTs) { best = i; bestTs = d.second } }
        if (best < 0) Edit(false, "This download is not listed in bg_download_history.json.") else {
            val (a, b) = spans[best]
            val reA = Regex("(\"failed_attempts\"\\s*:\\s*)-?\\d+"); val reS = Regex("(\"state\"\\s*:\\s*)-?\\d+")
            var el = raw.substring(a, b)
            el = if (reA.containsMatchIn(el)) el.replace(reA) { it.groupValues[1] + attempts } else el.trimEnd().removeSuffix("}") + ",\"failed_attempts\":$attempts}"
            el = if (reS.containsMatchIn(el)) el.replace(reS) { it.groupValues[1] + "3" } else el.trimEnd().removeSuffix("}") + ",\"state\":3}"
            val w = write(p, raw.substring(0, a) + el + raw.substring(b), spans.size)
            if (!w.ok) w else {
                val back = entry(read(p), dest)
                if (back != null && back.attempts == attempts && back.state == 3) w else Edit(false, "The PS4 file did not keep the change.")
            }
        }
    } catch (e: Exception) { Edit(false, Ps4Api.friendly(e)) }

    /** Removes every entry for [dest] (the server forgets the download at its next start). ok=true also when it was not listed. */
    fun remove(p: Ps4, dest: String): Edit = try {
        var raw = readText(p); val total = spans(raw).size; var removed = 0
        while (removed < 50) {
            val sp = spans(raw); val idx = sp.indexOfLast { destOf(raw, it)?.first == dest }
            if (idx < 0) break
            val (a, b) = sp[idx]; var s = a; var e = b
            var i = a - 1; while (i >= 0 && raw[i].isWhitespace()) i--
            if (i >= 0 && raw[i] == ',') s = i
            else { var j = b; while (j < raw.length && raw[j].isWhitespace()) j++; if (j < raw.length && raw[j] == ',') e = j + 1 }
            raw = raw.substring(0, s) + raw.substring(e); removed++
        }
        if (removed == 0) Edit(true, "") else {
            val w = write(p, raw, total - removed)
            if (!w.ok) w else if (entry(read(p), dest) == null) w else Edit(false, "The PS4 file still lists this download.")
        }
    } catch (e: Exception) { Edit(false, Ps4Api.friendly(e)) }
}
