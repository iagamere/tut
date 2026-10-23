package com.abdo.ps4monitor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 0 = follow phone, 1 = English, 2 = Arabic. Observable: changing it re-composes the whole UI instantly. */
object Lang {
    var mode by mutableIntStateOf(0)
    val isAr: Boolean get() = when (mode) { 1 -> false; 2 -> true; else -> Locale.getDefault().language == "ar" }
}
fun tr(en: String, ar: String) = if (Lang.isAr) ar else en

/**
 * Translates the English status/error sentences produced by the monitor engine at DISPLAY time.
 * Records keep their original English text, so switching language also re-translates old downloads.
 */
object Tx {
    private val m = mapOf(
        "Starting…" to "جارٍ البدء…",
        "PS4 server is not running. Launch PS4 server on the PS4." to "خادم PS4 لا يعمل. شغّل PS4 server على الـPS4.",
        "The PS4 is in rest mode and PS4 server is not answering. The app keeps watching and continues when the server answers again." to "الـPS4 في وضع الراحة وخادم PS4 لا يستجيب. التطبيق يواصل المراقبة وسيكمل عندما يستجيب الخادم.",
        "The PS4 is not reachable." to "الـPS4 غير متاح.",
        "Queued in PS4 server (it downloads one file at a time)." to "في قائمة انتظار خادم PS4 (يحمّل ملفًا واحدًا في كل مرة).",
        "PS4 server no longer lists this download." to "لم يعد خادم PS4 يعرض هذا التحميل.",
        "Old record: it cannot be followed by file path any more." to "سجل قديم: لم يعد ممكنًا تتبعه بمسار الملف.",
        "The app closed while sending. The request was NOT repeated; check the PS4 or retry." to "أُغلق التطبيق أثناء الإرسال. لم يُعَد إرسال الطلب؛ تحقق من الـPS4 أو أعد المحاولة.",
        "PS4 server accepted the request. Waiting for the PS4 to start." to "قبل PS4 server الطلب. بانتظار أن يبدأ الـPS4 التحميل.",
        "That is not a valid http(s) link." to "هذا ليس رابط http(s) صالحًا.",
        "This PS4 has no IP address yet." to "لا يوجد عنوان IP لهذا الـPS4.",
        "Download not found." to "التحميل غير موجود.",
        "The server asks for a token. Enter it in this PS4's settings in the app." to "الخادم يطلب رمزًا. أدخله في إعدادات هذا الجهاز في التطبيق.",
        "Could not change the setting on the server." to "تعذّر تغيير الإعداد على الخادم.",
        "The server asks for a token. Enter it in this PS4's settings." to "الخادم يطلب رمزًا. أدخله في إعدادات هذا الجهاز.",
        "This feature needs the custom server (abdo10 or newer)." to "هذه الميزة تحتاج الخادم المخصص (abdo10 أو أحدث).",
        "The server is too old for this feature: send the abdo10 server from the Server screen." to "الخادم قديم لهذه الميزة: أرسل خادم abdo10 من شاشة الخادم.",
        "File access through the server is switched off (Server screen)." to "الوصول للملفات عبر الخادم معطّل (شاشة الخادم).",
        "No such file or folder" to "لا يوجد ملف أو مجلد بهذا الاسم",
        "Destination already exists" to "الوجهة موجودة بالفعل",
        "Path is outside the allowed folders (fs_roots)" to "المسار خارج المجلدات المسموحة",
        "File API is disabled (fs_roots is empty)" to "واجهة الملفات معطّلة على الخادم",
        "Downloads do not need PS4 web with the custom server (the PS4 suspends PS4 server while a game runs). Files and covers do." to
            "التحميل لا يحتاج ويب PS4 server مع الخادم المخصص (الـPS4 يعلّق PS4 server أثناء تشغيل لعبة). أما الملفات والأغلفة فتحتاجه.",
        "That PS4 profile no longer exists." to "ملف هذا الـPS4 لم يعد موجودًا.",
        "Retry is only available for failed or not-started downloads." to "إعادة المحاولة متاحة فقط للتحميلات الفاشلة أو التي لم تبدأ.",
        "PS4 server accepted the request but no download activity was detected." to "قبل PS4 server الطلب لكن لم يُكتشف أي نشاط تحميل.",
        "Looking for the finished file…" to "جارٍ البحث عن الملف المكتمل…",
        "Checking downloaded file…" to "جارٍ فحص الملف المحمّل…",
        "PKG header (agrees with PFS image end)" to "ترويسة PKG (تطابق نهاية صورة PFS)",
        "PKG header" to "ترويسة PKG",
        "PKG header (unconfirmed)" to "ترويسة PKG (غير مؤكد)",
        "PS4 server reported a failure and retries automatically (up to 5 times)." to "أبلغ خادم PS4 عن فشل ويعيد المحاولة تلقائيًا (حتى 5 مرات).",
        "PS4 server gave up after repeated failures. The link may be expired or invalid, or the host refused the request." to "استسلم خادم PS4 بعد فشل متكرر. قد يكون الرابط منتهيًا أو غير صالح، أو رفض الموقع الطلب.",
        "PS4 server did not register this download." to "لم يسجّل خادم PS4 هذا التحميل.",
        "Waiting for PS4 server to register the download…" to "بانتظار أن يسجّل خادم PS4 التحميل…",
        "PS4 server reported success but the file is missing (it may have been deleted)." to "أبلغ خادم PS4 بالنجاح لكن الملف مفقود (ربما حُذف).",
        "Found in PS4 server (not started from this app)." to "وُجد في خادم PS4 (لم يبدأ من هذا التطبيق).",
        "PS4 server could not hand the download to PS4 server (port 6701). The background server may not be running: restart PS4 server on the PS4." to "تعذّر على PS4 server تسليم التحميل إلى خادم PS4 (المنفذ 6701). قد لا يكون الخادم يعمل: أعد تشغيل PS4 server على الـPS4.",
        "PS4 server" to "خادم PS4",
        "not reachable (is it running? port 6701)" to "غير متاح (هل يعمل؟ المنفذ 6701)",
        "PS4 server stopped retrying after 5 failed attempts. You can resume the download." to "توقف خادم PS4 عن المحاولة بعد 5 محاولات فاشلة. يمكنك استئناف التحميل.",
        "Paused by you. PS4 server was stopped and this download is marked stopped in bg_download_history.json." to "أوقفته مؤقتًا. أُوقف خادم PS4 وعُلِّم هذا التحميل كمتوقف في bg_download_history.json.",
        "Waiting for PS4 server… launch PS4 server on the PS4 so it reloads the download list." to "بانتظار خادم PS4… شغّل PS4 server على الـPS4 ليعيد تحميل قائمة التحميلات.",
        "Paused: PS4 server is not running and this download is marked stopped." to "متوقف مؤقتًا: خادم PS4 لا يعمل وهذا التحميل معلَّم كمتوقف.",
        "This download is not listed in bg_download_history.json." to "هذا التحميل غير مدرج في bg_download_history.json.",
        "The edited file would not be valid JSON; nothing was written." to "الملف المعدَّل لن يكون JSON صالحًا؛ لم يُكتب شيء.",
        "The PS4 file did not keep the change." to "لم يحتفظ ملف الـPS4 بالتغيير.",
        "known" to "معروف",
        "Queued on the server." to "في قائمة الانتظار على الخادم.",
        "Refreshing the expired link…" to "جارٍ تجديد الرابط المنتهي…",
        "Paused on the PS4 server." to "متوقف مؤقتًا على خادم الـPS4.",
        "The link no longer works." to "الرابط لم يعد يعمل.",
        "The download failed." to "فشل التحميل.",
        "Paused by you. Your other downloads keep running." to "أوقفته أنت. تحميلاتك الأخرى تواصل العمل.",
        "Resuming…" to "جارٍ الاستئناف…",
        "Link sent again; the download continues from where it stopped." to "أُعيد إرسال الرابط؛ سيكمل التحميل من حيث توقف.",
        "The link returned a web page instead of a file (expired link, quota exceeded or login page)" to "أعاد الرابط صفحة ويب بدل الملف (رابط منتهٍ أو تجاوز الحصة أو صفحة دخول).",
        "Disk write failed (disk full or storage error)" to "فشلت الكتابة على القرص (القرص ممتلئ أو خطأ في التخزين).",
        "No data for 45 seconds" to "لا بيانات منذ 45 ثانية",
        "The PS4 did not answer in time." to "لم يستجب الـPS4 في الوقت المناسب.",
        "Cannot reach the PS4 web server. Is PS4 server running with its web server enabled?" to "تعذّر الوصول إلى خادم الويب في الـPS4. هل PS4 server يعمل وخادم الويب مفعّل؟",
        "PS4 address not found." to "عنوان الـPS4 غير موجود.",
        "PS4 web connection problem." to "مشكلة في اتصال ويب الـPS4.",
        "Timeout" to "انتهت المهلة",
        "PS4 web" to "ويب PS4 server",
        "OK" to "يعمل",
    )
    private val pats: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("This link is already being downloaded to (.*)\\.") to { r -> "هذا الرابط قيد التحميل بالفعل إلى ${r.groupValues[1]}." },
        Regex("No download progress detected for (\\d+) s\\.") to { r -> "لم يُكتشف تقدم في التحميل منذ ${r.groupValues[1]} ثانية." },
        Regex("The finished file is (.*) but (.*) was expected\\.") to { r -> "حجم الملف المكتمل ${r.groupValues[1]} لكن المتوقع ${r.groupValues[2]}." },
        Regex("The finished file is not a PS4 PKG \\(size (.*)\\)\\. .*") to { r -> "الملف المكتمل ليس PKG للـPS4 (الحجم ${r.groupValues[1]}). غالبًا أعاد الموقع صفحة خطأ بدل اللعبة." },
        Regex("PS4 server answered HTTP (\\d+)\\.") to { r -> "أجاب PS4 server برمز HTTP ${r.groupValues[1]}." },
        Regex("PS4 server answered HTTP (\\d+)\\.") to { r -> "أجاب خادم PS4 برمز HTTP ${r.groupValues[1]}." },
        Regex("The server is retrying automatically \\(attempt (\\d+)\\): (.*)") to { r -> "الخادم يعيد المحاولة تلقائيًا (المحاولة ${r.groupValues[1]}): ${t(r.groupValues[2])}" },
        Regex("The link expired and was refreshed automatically \\((\\d+)/(\\d+)\\)\\.") to { r -> "انتهى الرابط وجُدِّد تلقائيًا (${r.groupValues[1]}/${r.groupValues[2]})." },
        Regex("(\\d+) connection\\(s\\)") to { r -> "${r.groupValues[1]} اتصال" },
        Regex("The server will retry automatically \\((\\d+)/(\\d+)\\): (.*)") to { r -> "سيعيد الخادم المحاولة تلقائيًا (${r.groupValues[1]}/${r.groupValues[2]}): ${t(r.groupValues[3])}" },
        Regex("HTTP (\\d+): the link is expired or forbidden\\. .*") to { r -> "HTTP ${r.groupValues[1]}: الرابط منتهٍ أو ممنوع. أعد إرسال الرابط (بنفس الوجهة) فيكمل التحميل." },
        Regex("The link no longer works: (.*)\\. Send the link again .*") to { r -> "الرابط لم يعد يعمل: ${r.groupValues[1]}. أعد إرسال الرابط (بنفس الوجهة) فيكمل التحميل." },
        Regex("Not enough free space: need (.*), free (.*)") to { r -> "لا توجد مساحة كافية: المطلوب ${r.groupValues[1]} والمتاح ${r.groupValues[2]}." },
        Regex("PS4 server rejected the request\\.") to { _ -> "رفض PS4 server الطلب." },
        Regex("PS4 server rejected the request: (.*)") to { r -> "رفض PS4 server الطلب: ${r.groupValues[1]}" },
    )
    fun t(s: String): String {
        if (!Lang.isAr || s.isBlank()) return s
        m[s]?.let { return it }
        for ((r, f) in pats) r.matchEntire(s)?.let { return f(it) }
        val i = s.indexOf(": ")
        if (i > 0) { val l = s.substring(0, i); val r = s.substring(i + 2); val a = t(l); val b = t(r); if (a != l || b != r) return "$a: $b" }
        return s
    }
    fun lines(s: String) = if (!Lang.isAr) s else s.lines().joinToString("\n") { t(it) }
    fun ev(s: String): String { if (!Lang.isAr) return s; val i = s.indexOf("  "); return if (i < 0) t(s) else s.substring(0, i) + "  " + t(s.substring(i + 2)) }
}
