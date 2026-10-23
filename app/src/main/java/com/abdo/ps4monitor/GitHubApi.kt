package com.abdo.ps4monitor

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubException(val code: Int, message: String) : Exception(message)

data class RunInfo(val id: Long, val number: Long, val status: String, val conclusion: String?, val url: String)
data class StepInfo(val name: String, val status: String, val conclusion: String?)
data class Asset(val id: Long, val name: String, val size: Long)
data class ReleaseInfo(val tag: String, val assets: List<Asset>)

/**
 * Minimal GitHub REST client. The token is only ever placed in the Authorization header;
 * it is never logged and never included in exception messages.
 */
class GitHubApi(private val token: String, owner: String, repo: String, apiRoot: String = "https://api.github.com") {
    companion object { const val WORKFLOW_FILE = "build.yml" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val base = "$apiRoot/repos/$owner/$repo"
    private val json = "application/json".toMediaType()

    private fun builder(url: String) = Request.Builder().url(url)
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }   // blank = going through the proxy, which adds it
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun message(body: String, code: Int): String {
        val m = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
        return m.ifBlank { "HTTP $code" }
    }

    private fun exec(b: Request.Builder): String {
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GitHubException(r.code, message(body, r.code))
            return body
        }
    }

    private fun get(path: String) = JSONObject(exec(builder("$base$path")))
    private fun post(path: String, o: JSONObject?) =
        JSONObject(exec(builder("$base$path").post((o?.toString() ?: "{}").toRequestBody(json))))

    // ---------- settings test ----------
    /** Returns (defaultBranch, canPush, workflowPresent). Throws GitHubException on 401/403/404. */
    fun check(): Triple<String, Boolean, Boolean> {
        val r = get("")
        val branch = r.optString("default_branch", "main")
        val push = r.optJSONObject("permissions")?.optBoolean("push", false) ?: false
        val wf = try { get("/actions/workflows/$WORKFLOW_FILE"); true } catch (e: GitHubException) {
            if (e.code == 404) false else throw e
        }
        return Triple(branch, push, wf)
    }

    // ---------- upload: ONE commit via the Git Data API ----------
    /** Adds [path] with [bytes] on the default branch in a single commit. Returns the new commit sha. */
    fun commitFile(path: String, bytes: ByteArray, commitMessage: String): String {
        val branch = get("").optString("default_branch", "main")
        val headSha = try {
            get("/git/ref/heads/$branch").getJSONObject("object").getString("sha")
        } catch (e: GitHubException) {
            if (e.code == 404 || e.code == 409)
                throw GitHubException(e.code, "branch '$branch' not found / repository is empty: push at least one commit first")
            throw e
        }
        val blob = post("/git/blobs", JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")).getString("sha")
        val baseTree = get("/git/commits/$headSha").getJSONObject("tree").getString("sha")
        val tree = post("/git/trees", JSONObject()
            .put("base_tree", baseTree)
            .put("tree", JSONArray().put(JSONObject()
                .put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob)))
        ).getString("sha")
        val commit = post("/git/commits", JSONObject()
            .put("message", commitMessage)
            .put("tree", tree)
            .put("parents", JSONArray().put(headSha))).getString("sha")
        exec(builder("$base/git/refs/heads/$branch")
            .patch(JSONObject().put("sha", commit).put("force", false).toString().toRequestBody(json)))
        return commit
    }

    // ---------- runs ----------
    private fun parseRun(o: JSONObject) = RunInfo(
        id = o.getLong("id"), number = o.getLong("run_number"), status = o.optString("status"),
        conclusion = if (o.isNull("conclusion")) null else o.optString("conclusion"),
        url = o.optString("html_url")
    )

    /** The run that GitHub started for [sha], or null if none exists (yet). */
    fun findRun(sha: String): RunInfo? {
        val arr = get("/actions/workflows/$WORKFLOW_FILE/runs?head_sha=$sha&per_page=5").getJSONArray("workflow_runs")
        return if (arr.length() == 0) null else parseRun(arr.getJSONObject(0))
    }

    fun getRun(id: Long): RunInfo = parseRun(get("/actions/runs/$id"))

    fun getSteps(runId: Long): List<StepInfo> {
        val jobs = get("/actions/runs/$runId/jobs").getJSONArray("jobs")
        if (jobs.length() == 0) return emptyList()
        val steps = jobs.getJSONObject(0).optJSONArray("steps") ?: return emptyList()
        return (0 until steps.length()).map {
            val s = steps.getJSONObject(it)
            StepInfo(s.optString("name"), s.optString("status"),
                if (s.isNull("conclusion")) null else s.optString("conclusion"))
        }
    }

    fun cancelRun(id: Long) {
        exec(builder("$base/actions/runs/$id/cancel").post("".toRequestBody(null)))
    }

    // ---------- releases ----------
    fun getRelease(tag: String): ReleaseInfo? {
        val o = try { get("/releases/tags/$tag") } catch (e: GitHubException) {
            if (e.code == 404) return null else throw e
        }
        val arr = o.optJSONArray("assets") ?: JSONArray()
        return ReleaseInfo(tag, (0 until arr.length()).map {
            val a = arr.getJSONObject(it)
            Asset(a.getLong("id"), a.getString("name"), a.optLong("size"))
        })
    }

    /** Streams a release asset to [dest]. OkHttp drops the Authorization header on the cross-host redirect. */
    fun downloadTo(assetId: Long, dest: File, onProgress: (Long) -> Unit) {
        val req = builder("$base/releases/assets/$assetId").header("Accept", "application/octet-stream").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw GitHubException(r.code, message(r.body?.string().orEmpty(), r.code))
            val body = r.body ?: throw GitHubException(r.code, "empty response")
            val tmp = File(dest.parentFile, dest.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); total += n; onProgress(total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw GitHubException(0, "could not write ${dest.name}")
        }
    }

    fun downloadText(assetId: Long): String {
        val tmp = File.createTempFile("asset", ".txt")
        try { downloadTo(assetId, tmp) {}; return tmp.readText() } finally { tmp.delete() }
    }
}
