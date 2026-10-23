package com.abdo.ps4monitor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Trophy discovery. NOTHING here is verified on a console: it only reads the account folders and dumps what it finds (file names, sizes,
 * and the tables/columns of any SQLite file) so the real layout can be seen before the trophy screen is built on it.
 */
object Ps4Trophy {
    val HOMES = listOf("/user/home", "/system_data/priv/home")
    /** Guesses for where themes live; each is simply listed, and a folder that does not exist is reported as such. */
    val THEME_ROOTS = listOf("/user/theme", "/user/themes", "/system_data/priv/theme", "/system_data/theme", "/system_ex/theme", "/user/system/theme")

    fun probe(ps4: Ps4, cache: File): List<String> {
        val out = ArrayList<String>()
        Ps4Server.setRoExtra(ps4, Ps4Server.roExtra().joinToString("\n"))      // re-sends the read-only folders (now including the account folders)
        fun add(s: String) { if (out.size < 400) out.add(s) }
        fun walk(path: String, depth: Int) {
            val l = try { Ps4Server.fsList(ps4, path, 8000) } catch (e: Exception) { add("$path: ${e.message}"); return }
            for (e in l.take(60)) {
                val p = "$path/${e.name}"
                if (e.isDir) { add("[dir] $p"); if (depth < 4) walk(p, depth + 1) }
                else {
                    add("$p  (${e.size} B)")
                    if (e.name.endsWith(".db", true) && e.size in 1..(32L shl 20)) dumpDb(ps4, p, e.size, cache, ::add)
                }
            }
        }
        for (t in THEME_ROOTS) { add("== theme folder $t"); walk(t, 2) }
        for (h in HOMES) {
            val ids = try { Ps4Server.fsList(ps4, h, 8000) } catch (e: Exception) { add("$h: ${e.message}"); continue }
            add("== $h: " + ids.joinToString { it.name })
            for (u in ids.filter { it.isDir }.take(12)) {
                val base = "$h/${u.name}"
                val l = try { Ps4Server.fsList(ps4, base, 8000) } catch (e: Exception) { add("$base: ${e.message}"); continue }
                add("-- $base: " + l.joinToString { it.name })
                l.firstOrNull { it.name == "username.dat" }?.let { n ->
                    runCatching { add("   name: " + String(Ps4Server.fsRead(ps4, "$base/${n.name}", 0, 64, 5000), Charsets.UTF_8).trim { c -> c == '\u0000' || c.isWhitespace() }) }
                }
                l.filter { it.isDir && (it.name.contains("troph", true) || it.name.contains("theme", true)) }.forEach { walk("$base/${it.name}", 1) }
            }
        }
        return out
    }

    private fun dumpDb(ps4: Ps4, path: String, size: Long, cache: File, add: (String) -> Unit) {
        val f = File(cache, "probe.db")
        try {
            f.outputStream().use { Ps4Server.fsCopyTo(ps4, path, size, it) { } }
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val tables = ArrayList<String>()
                db.rawQuery("select name from sqlite_master where type='table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
                for (t in tables.take(40)) {
                    val n = db.rawQuery("select count(*) from \"$t\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1 }
                    val cols = db.rawQuery("select * from \"$t\" limit 1", null).use { c -> c.columnNames.joinToString() }
                    add("     table $t [$n rows]: $cols")
                }
            } finally { db.close() }
        } catch (e: Exception) { add("     (cannot read as SQLite: ${e.message})") }
        finally { f.delete() }
    }
}
