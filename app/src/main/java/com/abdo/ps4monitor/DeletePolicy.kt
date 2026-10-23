package com.abdo.ps4monitor

/**
 * The ONE place that decides what this app may ever delete on the console. Pure functions (no network, no UI), so it is tested on its own.
 *
 * Rule of thumb: everything is protected unless it sits strictly INSIDE one of a few user-content zones:
 *   - the capture folders (screenshots / videos),
 *   - the PS4's download folder chosen in the app,
 *   - the contents of a USB drive (/mnt/usbN), except folders that look like console data.
 * On top of that a hard list of system / save / account / homebrew-loader locations (and the ancestors of those) is NEVER deletable, even if a
 * zone were misconfigured. Installed games are not deleted with this check at all: they go through [gameTargets] (the Games screen, double confirmation).
 * Every function re-checks, the UI is not trusted.
 */
object DeletePolicy {
    enum class Why { OK, BAD_PATH, TOP_LEVEL, ZONE_ROOT, SYSTEM, GAME_FILES, SAVES, TROPHIES, ACCOUNTS, LOADER, SYSTEM_FILE, OUTSIDE_ZONES, SWITCHED_OFF }
    class Verdict(val ok: Boolean, val why: Why, val zone: String? = null)

    /** Locations that are never deletable, nor anything inside, nor any folder that contains one of them. All lower case. */
    private val HARD = listOf(
        "/system", "/system_ex", "/system_data", "/preinst", "/preinst2", "/update", "/boot", "/dev", "/eap_user", "/hostapp", "/sandbox", "/mnt/sandbox",
        "/user/home", "/user/system", "/user/trophy", "/user/app", "/user/appmeta", "/user/patch", "/user/addcont", "/user/license", "/user/priv",
        "/user/theme", "/user/themes", "/user/download", "/user/av_contents/thumbnails", "/user/av_contents/temp",
        "/data/goldhen", "/data/etahen", "/data/ezremote-client", "/data/payloads", "/data/plugins", "/data/hen", "/data/save", "/data/savedata")
    /** Names that are protected wherever they are (a game's executable, its system files, save data, trophy keys, kernel modules). */
    private val NAMES = setOf("eboot.bin", "param.sfo", "sce_sys", "sce_module", "sce_discmap.plt", "savedata", "sealedkey", "trophy", "npbind.dat", "keystone", "nptitle.dat", "tropdata")
    private val EXTS = setOf("sprx", "self", "prx")
    /** Folder names that mean "console data" inside a USB drive. */
    private val USB_RESERVED = setOf("ps4", "ps5", "savedata", "system", "system_data", "user", "app", "appmeta", "patch", "addcont", "home", "trophy", "sce_sys")

    val CAPTURE_ZONES = listOf("/user/av_contents/photo", "/user/av_contents/video")
    private val USB = Regex("^/mnt/usb[0-7]$")

    /** Null when the path is not a clean absolute path. Removes a trailing slash. No "..", no ".", no empty segments, no control characters. */
    fun clean(path: String): String? {
        if (path.isEmpty() || path.length > 1024 || path[0] != '/') return null
        if (path.any { it.code < 32 || it == '\\' }) return null
        val p = if (path.length > 1) path.trimEnd('/') else path
        if (p.isEmpty()) return null
        val segs = p.substring(1).split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." }) return null
        return p
    }
    private fun lower(p: String) = p.lowercase()
    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun depth(p: String) = p.trim('/').split('/').count { it.isNotEmpty() }

    /** [downloadDir]: the PS4's download folder from the app's settings. */
    fun zones(downloadDir: String): List<String> {
        val d = clean(downloadDir)?.let { lower(it) }
        val usable = d != null && depth(d) >= 2 && HARD.none { under(d, it) || under(it, d) } && !USB.matches(d)
        return CAPTURE_ZONES + (if (usable) listOf(downloadDir.let { clean(it)!! }) else emptyList())
    }

    /** May [path] be deleted by the generic delete? The path is checked for itself; a folder is accepted when it is inside a zone and is not protected. */
    fun check(path: String, downloadDir: String, enabled: Boolean = true): Verdict {
        val p = clean(path) ?: return Verdict(false, Why.BAD_PATH)
        val lp = lower(p)
        if (depth(lp) < 2) return Verdict(false, Why.TOP_LEVEL)
        // installed games have their own, double-confirmed route
        if (Regex("^/(user|mnt/ext\\d)/(app|patch|addcont|appmeta)(/|$)").containsMatchIn(lp)) return Verdict(false, Why.GAME_FILES)
        HARD.forEach { h ->
            if (under(lp, h) || under(h, lp)) return Verdict(false, when {
                h.startsWith("/user/home") || h == "/user/priv" -> Why.ACCOUNTS
                h.contains("trophy") -> Why.TROPHIES
                h.startsWith("/data/save") -> Why.SAVES
                h.startsWith("/data/") -> Why.LOADER
                else -> Why.SYSTEM
            })
        }
        val segs = lp.substring(1).split('/')
        if (segs.any { it in NAMES }) return Verdict(false, if (segs.any { it == "savedata" }) Why.SAVES else Why.SYSTEM_FILE)
        if (segs.last().substringAfterLast('.', "") in EXTS) return Verdict(false, Why.SYSTEM_FILE)
        // zones
        // the console's file system is case-sensitive: a zone only matches with its exact spelling (protection above ignores case, the permission does not)
        val usbRoot = Regex("^/mnt/usb[0-7]").find(p)?.value
        val candidates = zones(downloadDir).toMutableList()
        if (usbRoot != null) candidates.add(usbRoot)
        for (z in candidates) {
            if (!under(p, z)) continue
            if (p == z) return Verdict(false, Why.ZONE_ROOT)
            if (z == usbRoot && segs.drop(2).any { it in USB_RESERVED }) return Verdict(false, Why.SYSTEM)
            return if (enabled) Verdict(true, Why.OK, z) else Verdict(false, Why.SWITCHED_OFF, z)
        }
        return Verdict(false, Why.OUTSIDE_ZONES)
    }

    // ---------------- games ----------------
    private val GAME_ID = Regex("^CUSA\\d{5}$")
    /** Only PS4 titles. System applications (NPXS...) and anything else never. */
    fun isGameId(id: String) = GAME_ID.matches(id)
    /** The three folders removed for a game: its install folder, its update (patch) and its add-on content. Saves, trophies, captures and the title's metadata stay. */
    fun gameTargets(id: String): List<String> = if (isGameId(id)) listOf("/user/app/$id", "/user/patch/$id", "/user/addcont/$id") else emptyList()
    /** The parent folders the server must allow (temporarily) for these deletes. */
    val GAME_PARENTS = listOf("/user/app", "/user/patch", "/user/addcont")
    fun isGameTarget(path: String): Boolean { val p = clean(path) ?: return false; return Regex("^/user/(app|patch|addcont)/(CUSA\\d{5})$").matchEntire(p) != null }
}
