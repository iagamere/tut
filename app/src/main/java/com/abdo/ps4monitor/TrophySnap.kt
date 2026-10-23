package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trophy folder snapshot and comparison. Read-only: it only lists folders (name, size, modified time) through the server.
 * Purpose: find out which files change when a trophy is earned offline. NOTHING about the result is assumed here.
 * Limit: a file whose size and modified time did not change is reported as unchanged even if its content changed.
 */
object TrophySnap {
    private const val MAX_ENTRIES = 4000
    private const val PER_DIR = 500
    class Item(val path: String, val size: Long, val mtime: Long, val dir: Boolean)
    class Snap(val at: Long, val items: List<Item>, val notes: List<String>)

    private fun key(ps4: Ps4) = "trsnap_" + ps4.id

    fun take(ps4: Ps4): Snap {
        val items = ArrayList<Item>(); val notes = ArrayList<String>()
        Ps4Server.setRoExtra(ps4, Ps4Server.roExtra().joinToString("\n"))   // makes sure the account folders are readable
        fun walk(path: String, depth: Int) {
            if (items.size >= MAX_ENTRIES) { if (notes.none { it.startsWith("Stopped") }) notes.add("Stopped at $MAX_ENTRIES entries."); return }
            val l = try { Ps4Server.fsList(ps4, path, 8000) } catch (e: Exception) { notes.add("$path: ${e.message}"); return }
            if (l.size > PER_DIR) notes.add("$path has ${l.size} entries; only the first $PER_DIR were recorded.")
            for (e in l.take(PER_DIR)) {
                val p = "$path/${e.name}"
                items.add(Item(p, e.size, e.mtime, e.isDir))
                if (e.isDir && depth < 4) walk(p, depth + 1)
            }
        }
        val homes = try { Ps4Server.fsList(ps4, "/user/home", 8000) } catch (e: Exception) { notes.add("/user/home: ${e.message}"); emptyList() }
        for (u in homes.filter { it.isDir }.take(16)) walk("/user/home/${u.name}/trophy", 1)
        walk("/user/trophy", 1)
        val s = Snap(System.currentTimeMillis(), items, notes)
        save(ps4, s)
        return s
    }

    private fun save(ps4: Ps4, s: Snap) {
        val a = JSONArray()
        for (i in s.items) a.put(JSONArray().put(i.path).put(i.size).put(i.mtime).put(if (i.dir) 1 else 0))
        val o = JSONObject().put("at", s.at).put("items", a)
        Store.sp.edit().putString(key(ps4), o.toString()).apply()
    }

    fun saved(ps4: Ps4): Snap? = try {
        val o = JSONObject(Store.sp.getString(key(ps4), null) ?: return null)
        val a = o.getJSONArray("items")
        Snap(o.optLong("at"), (0 until a.length()).map { val r = a.getJSONArray(it); Item(r.getString(0), r.getLong(1), r.getLong(2), r.getInt(3) == 1) }, emptyList())
    } catch (e: Exception) { null }

    private fun fmt(ms: Long) = if (ms <= 0) "?" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** Lines describing what differs between [old] and [now]. Folders are compared by modified time only. */
    fun diff(old: Snap, now: Snap): List<String> {
        val out = ArrayList<String>()
        val o = old.items.associateBy { it.path }; val n = now.items.associateBy { it.path }
        val allZero = now.items.isNotEmpty() && now.items.all { it.mtime <= 0 }
        if (allZero) out.add("NOTE: the server reported no modified times, so only size changes can be detected.")
        for (i in now.items.sortedBy { it.path }) {
            val b = o[i.path]
            if (b == null) out.add("NEW      ${i.path}" + if (i.dir) " [dir]" else "  (${i.size} B)")
            else if (!i.dir && (b.size != i.size || b.mtime != i.mtime)) out.add("CHANGED  ${i.path}  size ${b.size} -> ${i.size}, time ${fmt(b.mtime)} -> ${fmt(i.mtime)}")
            else if (i.dir && b.mtime != i.mtime) out.add("DIR-TIME ${i.path}  ${fmt(b.mtime)} -> ${fmt(i.mtime)}")
        }
        for (b in old.items.sortedBy { it.path }) if (n[b.path] == null) out.add("REMOVED  ${b.path}")
        if (out.none { !it.startsWith("NOTE") }) out.add("No difference in names, sizes or modified times (${now.items.size} entries compared).")
        out.add("Snapshot taken ${fmt(old.at)}; compared ${fmt(now.at)}.")
        return out
    }
}
