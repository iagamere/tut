package com.abdo.ps4monitor
import org.json.JSONObject

/** Result of a delete: [message] is ready to show (already in the app language). */
class DeleteResult(val ok: Boolean, val message: String)
/** Size of a folder measured by walking it: [complete] = false when the walk was stopped by the limit or by the user. */
class Measure(val bytes: Long, val files: Int, val dirs: Int, val complete: Boolean)

/**
 * Storage browser / delete operations. Every delete is checked by [DeletePolicy] HERE, right before the request, whatever the screen did.
 * NOT verified on a console: the server's /v2/fs/delete is used the same way the captures delete already uses it.
 */
object StorageOps {
    private const val DELETE_TIMEOUT = 900_000      // a big folder can take minutes
    private const val MAX_TREE = 20_000

    /** One server delete; every failure (including "server unreachable") becomes an OpResult, never an exception. */
    private fun rm(ps4: Ps4, path: String, timeoutMs: Int): Ps4Api.OpResult =
        try { Ps4Server.fsOp(ps4, "delete", JSONObject().put("path", path), timeoutMs) } catch (e: Exception) { Ps4Api.OpResult(false, Ps4Api.friendly(e)) }

    private fun missing(m: String) = m.startsWith("No such") || m.startsWith("Parent folder")

    /** Measures [path] by listing it recursively through the server (one request per folder, one at a time). */
    fun measure(ps4: Ps4, path: String, maxCalls: Int = 3000, cancelled: () -> Boolean = { false }): Measure {
        var bytes = 0L; var files = 0; var dirs = 0; var calls = 0
        val stack = ArrayDeque<String>(); stack.add(path)
        while (stack.isNotEmpty()) {
            if (cancelled() || calls >= maxCalls) return Measure(bytes, files, dirs, false)
            val d = stack.removeLast(); calls++
            val l = try { Ps4Server.fsList(ps4, d, 15_000) } catch (e: Exception) { continue }
            for (e in l) {
                if (e.isDir) { dirs++; stack.add(if (d.endsWith("/")) d + e.name else "$d/${e.name}") } else { files++; bytes += e.size.coerceAtLeast(0) }
            }
        }
        return Measure(bytes, files, dirs, true)
    }

    /** Deletes children of [root] depth-first by hand (files, then folders from the bottom). Used only when the server's own recursive delete failed. */
    private fun deleteTree(ps4: Ps4, root: String, allowed: (String) -> Boolean): Ps4Api.OpResult {
        val dirs = ArrayList<String>(); val files = ArrayList<String>()
        val stack = ArrayDeque<String>(); stack.add(root)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast(); dirs.add(d)
            if (dirs.size + files.size > MAX_TREE) return Ps4Api.OpResult(false, "The folder is too large to delete in pieces.")
            val l = try { Ps4Server.fsList(ps4, d, 20_000) } catch (e: Ps4Error) { return Ps4Api.OpResult(false, e.message ?: "Could not read the folder.") }
                    catch (e: Exception) { return Ps4Api.OpResult(false, Ps4Api.friendly(e)) }
            for (e in l) { val p = "$d/${e.name}"; if (e.isDir) stack.add(p) else files.add(p) }
        }
        for (f in files) {
            if (!allowed(f)) return Ps4Api.OpResult(false, "Refused: protected path.")
            val r = rm(ps4, f, 60_000)
            if (!r.ok && !missing(r.message)) return r
        }
        for (d in dirs.asReversed()) {
            if (!allowed(d)) return Ps4Api.OpResult(false, "Refused: protected path.")
            val r = rm(ps4, d, 60_000)
            if (!r.ok && !missing(r.message)) return r
        }
        return Ps4Api.OpResult(true, "")
    }

    private fun exists(ps4: Ps4, path: String): Boolean {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }; val name = path.substringAfterLast('/')
        return try { Ps4Server.fsList(ps4, parent, 20_000).any { it.name == name } } catch (e: Ps4Error) { if (missing(e.message ?: "")) false else throw e }
    }

    /** One file or folder chosen in the browser. */
    fun delete(ps4: Ps4, path: String): DeleteResult {
        val v = DeletePolicy.check(path, ps4.dest, Ps4Server.deleteAllowed())
        if (!v.ok) return DeleteResult(false, tr("Refused: this item is protected.", "رُفض: هذا العنصر محمي."))
        val zone = v.zone ?: return DeleteResult(false, tr("Refused.", "رُفض."))
        val (w, err) = Ps4Server.openWritable(ps4, listOf(zone))
        if (w == null) return DeleteResult(false, Tx.t(err ?: "The server is not reachable."))
        try {
            // the check is repeated for every single path the fallback touches
            val allowed = { p: String -> DeletePolicy.check(p, ps4.dest, true).ok }
            var r = rm(ps4, path, DELETE_TIMEOUT)
            if (!r.ok && !missing(r.message)) {
                val stillThere = try { exists(ps4, path) } catch (e: Exception) { true }
                if (stillThere) r = deleteTree(ps4, path, allowed)
            }
            val left = try { exists(ps4, path) } catch (e: Exception) { null }
            return when {
                left == false -> DeleteResult(true, tr("Deleted from the PS4.", "حُذف من الـPS4."))
                r.ok && left == null -> DeleteResult(true, tr("Deleted from the PS4.", "حُذف من الـPS4."))
                else -> DeleteResult(false, tr("Not deleted: ", "لم يُحذف: ") + Tx.t(r.message.ifBlank { "The item is still there." }))
            }
        } finally { Ps4Server.closeWritable(ps4, w) }
    }

    /**
     * Deletes an installed game completely: its install folder, its update and its add-on content (/user/app, /user/patch, /user/addcont of that title).
     * Saved games, trophies, captures and the title's metadata are NOT touched. Only CUSA ids; system applications can never be removed this way.
     * The console's own application database is not edited, so the icon may stay on the home screen until it is deleted there (Options > Delete).
     */
    fun deleteGame(ps4: Ps4, titleId: String, progress: (String) -> Unit = {}): DeleteResult {
        if (!Ps4Server.deleteAllowed()) return DeleteResult(false, tr("Deleting is switched off (Storage tab).", "الحذف معطّل (تبويب التخزين)."))
        val targets = DeletePolicy.gameTargets(titleId)
        if (targets.isEmpty()) return DeleteResult(false, tr("Refused: only PS4 games (CUSA…) can be deleted.", "رُفض: يمكن حذف ألعاب PS4 فقط (CUSA…)."))
        val (w, err) = Ps4Server.openWritable(ps4, DeletePolicy.GAME_PARENTS)
        if (w == null) return DeleteResult(false, Tx.t(err ?: "The server is not reachable."))
        try {
            val main = targets[0]
            val present = try { exists(ps4, main) } catch (e: Exception) { return DeleteResult(false, tr("Could not read the game folder: ", "تعذّرت قراءة مجلد اللعبة: ") + Tx.t(Ps4Api.friendly(e))) }
            if (!present) return DeleteResult(false, tr("The game folder was not found on the PS4.", "لم يُعثر على مجلد اللعبة في الـPS4."))
            val gone = ArrayList<String>(); var problem = ""
            for (t in targets) {
                if (!DeletePolicy.isGameTarget(t)) { problem = "protected path"; break }
                val there = try { exists(ps4, t) } catch (e: Exception) { problem = Tx.t(Ps4Api.friendly(e)); break }
                if (!there) continue
                progress(t.substringBeforeLast('/').substringAfterLast('/'))
                var r = rm(ps4, t, DELETE_TIMEOUT)
                if (!r.ok && !missing(r.message) && try { exists(ps4, t) } catch (e: Exception) { true }) r = deleteTree(ps4, t) { p -> p == t || p.startsWith("$t/") }
                val left = try { exists(ps4, t) } catch (e: Exception) { true }
                if (left) { problem = Tx.t(r.message.ifBlank { "The folder is still there." }); break } else gone.add(t)
            }
            return if (problem.isEmpty()) DeleteResult(true, tr("The game was deleted from the PS4.", "حُذفت اللعبة من الـPS4."))
                   else DeleteResult(false, tr("The game was not fully deleted: ", "لم تُحذف اللعبة بالكامل: ") + problem + (if (gone.isNotEmpty()) "\n" + tr("Already removed: ", "حُذف منها: ") + gone.joinToString { it.substringBeforeLast('/').substringAfterLast('/') } else ""))
        } finally { Ps4Server.closeWritable(ps4, w) }
    }
}
