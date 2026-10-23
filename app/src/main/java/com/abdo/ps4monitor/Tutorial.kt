package com.abdo.ps4monitor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** First-run walkthrough. Shown once (pref "tour_done"); can be reopened from Settings. */
object Tour {
    val show = mutableStateOf(false)
    private var checked = false

    /** Called once per process: opens the tour if the user never finished or skipped it. */
    fun startIfFirstRun() {
        if (checked) return
        checked = true
        val done = try { Store.sp.getBoolean("tour_done", false) } catch (e: Exception) { true }
        if (!done) show.value = true
    }
    fun open() { show.value = true }
    fun finish() {
        show.value = false
        try { Store.sp.edit().putBoolean("tour_done", true).apply() } catch (e: Exception) { }
    }
}

private class TourStep(@DrawableRes val icon: Int, val title: String, val body: String)

private fun tourSteps(): List<TourStep> = listOf(
    TourStep(R.drawable.ic_home,
        tr("Welcome", "أهلاً بك"),
        tr("This app downloads games and files straight onto your PS4 and lets you watch the progress from your phone.\n\nThis short tour explains everything. You can skip it at any time and reopen it later from Settings.",
            "هذا التطبيق يحمّل الألعاب والملفات مباشرة إلى الـPS4 ويتيح لك متابعة التقدم من هاتفك.\n\nهذه جولة قصيرة تشرح كل شيء. يمكنك تخطيها في أي وقت وإعادة فتحها لاحقاً من الإعدادات.")),
    TourStep(R.drawable.ic_console,
        tr("1. Prepare the PS4", "1. تجهيز الـPS4"),
        tr("Your PS4 must already be jailbroken and GoldHEN loaded.\n\nIn GoldHEN's settings turn on \"Enable PayLoader Server\" (port 9090). The app uses it to send our server to the console.\n\nThe PS4 and the phone must be on the same Wi-Fi.",
            "يجب أن يكون الـPS4 مكسور الحماية (جيلبريك) وGoldHEN يعمل.\n\nفي إعدادات GoldHEN فعّل «Enable PayLoader Server» (المنفذ 9090). التطبيق يستخدمه لإرسال السيرفر إلى الجهاز.\n\nيجب أن يكون الـPS4 والهاتف على نفس شبكة الواي فاي.")),
    TourStep(R.drawable.ic_add,
        tr("2. Add your PS4", "2. إضافة جهازك"),
        tr("On the Home tab tap \"Add PS4\" (or Settings > PS4 consoles). Type the console's IP address, or let the app search for it.\n\nThe IP is shown on the PS4 under Settings > Network > View Connection Status.",
            "في تبويب الرئيسية اضغط «إضافة PS4» (أو الإعدادات > أجهزة PS4). اكتب عنوان IP للجهاز أو دع التطبيق يبحث عنه.\n\nتجد الـIP في الـPS4 من الإعدادات > الشبكة > عرض حالة الاتصال.")),
    TourStep(R.drawable.ic_download,
        tr("3. Send the server", "3. إرسال السيرفر"),
        tr("Open Settings > PS4 server and tap send. The app sends our download server to the PS4 through GoldHEN. Do this each time the console is restarted.\n\nBy default it also copies the server file to /data/payloads (through GoldHEN's FTP) in case you want to use GoldHEN's AutoRun; this can be switched off on the same screen.",
            "افتح الإعدادات > خادم PS4 واضغط إرسال. يرسل التطبيق خادم التحميل إلى الـPS4 عبر GoldHEN. افعل ذلك في كل مرة يُعاد فيها تشغيل الجهاز.\n\nوبشكل افتراضي ينسخ أيضاً ملف السيرفر إلى /data/payloads (عبر FTP الخاص بـGoldHEN) إن أردت استخدام AutoRun؛ ويمكن إيقاف ذلك من نفس الشاشة.")),
    TourStep(R.drawable.ic_link,
        tr("4. Send a download", "4. إرسال تحميل"),
        tr("Tap \"Link\" on Home and paste a direct link, or open a page in the \"Browser\" tab and tap a file. Share a link from any app to this app also works.\n\nOn the Home screen, \"Server in use\" shows which server receives your downloads. Tap it to change it.",
            "اضغط «رابط» في الرئيسية والصق رابط تحميل مباشر، أو افتح صفحة في تبويب «المتصفح» واضغط على ملف. كما تعمل مشاركة رابط من أي تطبيق إلى هذا التطبيق.\n\nفي الرئيسية يعرض «السيرفر المستخدم» أي سيرفر يستقبل تحميلاتك. اضغط عليه لتغييره.")),
    TourStep(R.drawable.ic_schedule,
        tr("5. Follow the downloads", "5. متابعة التحميلات"),
        tr("The Downloads tab lists the queue with speed and progress. Open a download to pause, resume or delete it.\n\nThe app can also notify you while a download runs and when it ends.",
            "تبويب التحميلات يعرض الطابور مع السرعة والتقدم. افتح أي تحميل لإيقافه مؤقتاً أو استئنافه أو حذفه.\n\nيمكن للتطبيق أيضاً إشعارك أثناء التحميل وعند انتهائه.")),
    TourStep(R.drawable.ic_folder,
        tr("6. Files on the console", "6. ملفات الجهاز"),
        tr("The Files screen lets you browse, rename, move and delete files on the PS4 through the server. Deleting cannot be undone, so check before you confirm.",
            "شاشة الملفات تتيح لك تصفح ملفات الـPS4 وإعادة تسميتها ونقلها وحذفها عبر السيرفر. الحذف لا يمكن التراجع عنه، فتأكد قبل التأكيد.")),
    TourStep(R.drawable.ic_check_circle,
        tr("The ? buttons", "أزرار علامة ؟"),
        tr("Next to every important control you will find a small round \"?\" button. Tap it to see exactly what that control does.\n\nYou can reopen this tour from Settings > Show the tutorial.",
            "بجانب كل خاصية مهمة ستجد زراً دائرياً صغيراً فيه «؟». اضغط عليه لتعرف بالضبط ماذا يفعل هذا العنصر.\n\nيمكنك إعادة فتح هذه الجولة من الإعدادات > عرض الشرح."))
)

@Composable fun TourOverlay() {
    if (!Tour.show.value) return
    val steps = remember { tourSteps() }
    var i by remember { mutableIntStateOf(0) }
    val last = i == steps.size - 1
    val s = steps[i]
    Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!last) TextButton(onClick = { Tour.finish() }) { Text(tr("Skip", "تخطي")) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Ico(s.icon, 44.dp, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(s.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    steps.indices.forEach { k ->
                        Box(Modifier.padding(horizontal = 3.dp).size(if (k == i) 10.dp else 7.dp).clip(CircleShape)
                            .background(if (k == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) OutlinedButton(onClick = { i -= 1 }, modifier = Modifier.weight(1f)) { Text(tr("Back", "السابق")) }
                    Button(onClick = { if (last) Tour.finish() else i += 1 }, modifier = Modifier.weight(1f)) {
                        Text(if (last) tr("Done", "تم") else tr("Continue", "استمرار"))
                    }
                }
            }
        }
    }
}

/** Small round "?" button; opens a dialog that explains what the control next to it does. */
@Composable fun HelpDot(title: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, modifier = modifier.size(24.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text("?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(tr("OK", "حسناً")) } })
}

/** Section title with a "?" button at the end of the line. */
@Composable fun SectionTitleHelp(t: String, help: String) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { SectionTitle(t) }
        HelpDot(t, help)
    }
