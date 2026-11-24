package com.anwar.walkie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.code.scanner.GmsBarcodeScanning

private val Navy = Color(0xFF0F1B2D)
private val Panel = Color(0xFF182A44)
private val Amber = Color(0xFFFFB020)
private val Green = Color(0xFF2ECC71)
private val Red = Color(0xFFE5484D)
private val Muted = Color(0xFF8FA3BF)

val CHANNELS = listOf(
    "القناة العامة", "قناة الفريق", "قناة الأصدقاء", "قناة العائلة",
    "قناة الرحلة", "قناة العمل", "قناة الطوارئ", "قناة خاصة"
)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { App(prefs) }
            }
        }
    }

    // التحدث بأزرار الصوت (والتطبيق في الواجهة)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolKey(keyCode) && prefs.volPtt && Hub.connected.value) {
            if (event.repeatCount == 0) Hub.ptt(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolKey(keyCode) && prefs.volPtt && Hub.connected.value) {
            Hub.ptt(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolKey(k: Int) = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
}

@Composable
fun App(prefs: Prefs) {
    val ctx = LocalContext.current
    val connected by Hub.connected.collectAsState()
    val peers by Hub.peers.collectAsState()
    val call by Hub.call.collectAsState()
    val talking by Hub.talking.collectAsState()
    var channel by remember { mutableIntStateOf(prefs.channel) }
    var listen by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var speaker by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrInfo by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Exception) { null } }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    // إبقاء الشاشة مضاءة
    val view = LocalView.current
    DisposableEffect(connected) {
        view.keepScreenOn = connected && prefs.keepScreen
        onDispose { view.keepScreenOn = false }
    }

    // نغمة واهتزاز عند بدء/إنهاء التحدث
    LaunchedEffect(talking) {
        if (connected) {
            if (prefs.beep) tone?.startTone(if (talking) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 80)
            if (talking) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // وضع الصوت أثناء المكالمة
    LaunchedEffect(call.state == 3, speaker) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (call.state == 3) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = speaker }
        } else {
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
            muted = false
        }
    }

    // ---- الأذونات والتشغيل
    fun startService() = ContextCompat.startForegroundService(ctx, Intent(ctx, WalkieService::class.java))

    val micPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startService()
        else toast("لا يمكن التشغيل بدون إذن الميكروفون")
    }

    fun toggle() {
        if (connected) {
            ctx.stopService(Intent(ctx, WalkieService::class.java))
            return
        }
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) startService() else micPerms.launch(need.toTypedArray())
    }

    // أذونات الشبكة (هوتسبوت/انضمام)
    var pendingNet by remember { mutableStateOf<(() -> Unit)?>(null) }
    val netPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) pendingNet?.invoke() else toast("الأذونات مطلوبة لإنشاء/الانضمام إلى الشبكة")
        pendingNet = null
    }
    fun withNetPerms(action: () -> Unit) {
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) action()
        else { pendingNet = action; netPerms.launch(need.toTypedArray()) }
    }

    fun applyCode(code: String) { prefs.code = code; Hub.radio?.code = code }

    fun createNetwork() = withNetPerms {
        NetHelper.startHotspot(ctx, prefs.code,
            onReady = { ssid, qr -> qrBitmap = NetHelper.qr(qr); qrInfo = "الشبكة: $ssid"; if (!connected) toggle() },
            onFail = { toast(it) })
    }

    fun scanQr() = withNetPerms {
        GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { b ->
                val j = b.rawValue?.let { NetHelper.parse(it) }
                if (j == null) { toast("هذا ليس رمز شبكة صالحاً"); return@addOnSuccessListener }
                applyCode(j.code)
                toast("جارٍ الانضمام إلى ${j.ssid} ...")
                NetHelper.join(ctx, j, onOk = { toast("تم الانضمام للشبكة"); if (!connected) toggle() }, onFail = { toast(it) })
            }
            .addOnFailureListener { toast("تعذر فتح الماسح: ${it.message}") }
    }

    val onChannel = peers.filter { it.channel == channel }

    // ================================================================ الشاشة
    Box(Modifier.fillMaxSize().background(Navy)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (connected) Green else Amber)
                        .clickable { toggle() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(if (connected) "متصل" else "غير متصل", color = Navy, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text("لاسلكي الفريق", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("المتصلون", color = Muted, fontSize = 12.sp)
                    Text(if (connected) "${1 + onChannel.size}" else "0", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(CHANNELS[channel - 1], color = Muted, fontSize = 13.sp)
                    Text("CH %02d".format(channel), color = Amber, fontSize = 44.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الحالة", color = Muted, fontSize = 12.sp)
                    Text(
                        when { !connected -> "—"; talking -> "إرسال"; onChannel.isEmpty() -> "انتظار"; else -> "جاهز" },
                        color = if (connected) Green else Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            val scale by animateFloatAsState(if (talking) 1.08f else 1f, label = "ptt")
            Box(
                Modifier.size(210.dp).scale(scale).clip(CircleShape)
                    .background(if (talking) Green else if (connected) Amber else Color(0xFF3A4B66))
                    .pointerInput(connected) {
                        detectTapGestures(onPress = {
                            if (!connected) { toast("اضغط «غير متصل» للتشغيل أولاً"); return@detectTapGestures }
                            Hub.ptt(true)
                            tryAwaitRelease()
                            Hub.ptt(false)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (talking) "جارٍ التحدث..." else "اضغط مطولاً\nللتحدث",
                    color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("السماعة", if (listen) "مفعّلة" else "مكتومة", Modifier.weight(1f)) {
                    listen = !listen; Hub.radio?.listen = listen
                }
                Tile("القنوات", "CH %02d".format(channel), Modifier.weight(1f)) { dialog = "channels" }
                Tile("المشاركون", "${peers.size}", Modifier.weight(1f)) { dialog = "peers" }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    if (connected) toggle()
                    NetHelper.leave(ctx); qrBitmap = null
                    toast("تم إيقاف الاتصال والشبكة المحلية")
                }) { Text("خروج", color = Muted) }
                TextButton(onClick = { dialog = "network" }) { Text("الشبكة / QR", color = Amber) }
                TextButton(onClick = { dialog = "settings" }) { Text("الإعدادات", color = Muted) }
            }

            Text(
                "إعداد وهندسة: المهندس أنور محمد عايض الأشول",
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }

        // ---- شاشة المكالمة
        if (call.state != 0) {
            Column(
                Modifier.fillMaxSize().background(Navy).statusBarsPadding().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(120.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Text(call.name.take(1), color = Amber, fontSize = 48.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(16.dp))
                Text(call.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (call.state) { 1 -> "جارٍ الاتصال..."; 2 -> "مكالمة واردة"; else -> "في مكالمة — الصوت مفتوح للطرفين" },
                    color = Muted, fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))
                when (call.state) {
                    2 -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        RoundBtn("رفض", Red) { Hub.radio?.endCall() }
                        RoundBtn("رد", Green) { Hub.radio?.accept() }
                    }
                    1 -> RoundBtn("إلغاء", Red) { Hub.radio?.endCall() }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RoundBtn(if (muted) "إلغاء الكتم" else "كتم", if (muted) Amber else Panel) {
                            muted = !muted; Hub.radio?.muted = muted
                        }
                        RoundBtn(if (speaker) "السماعة" else "الأذن", if (speaker) Amber else Panel) { speaker = !speaker }
                        RoundBtn("إنهاء", Red) { Hub.radio?.endCall() }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ================================================================ النوافذ
    when (dialog) {
        "channels" -> AlertDialog(
            onDismissRequest = { dialog = null }, confirmButton = {},
            title = { Text("اختر القناة") },
            text = {
                Column {
                    CHANNELS.forEachIndexed { i, n ->
                        Text(
                            "CH %02d  —  %s".format(i + 1, n),
                            fontWeight = if (i + 1 == channel) FontWeight.Bold else FontWeight.Normal,
                            color = if (i + 1 == channel) Color(0xFFB36B00) else Color.Black,
                            modifier = Modifier.fillMaxWidth().clickable {
                                channel = i + 1; prefs.channel = channel; Hub.radio?.channel = channel; dialog = null
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        )
        "peers" -> {
            val saved = prefs.contacts
            val onlineIds = peers.map { it.uid }.toSet()
            AlertDialog(
                onDismissRequest = { dialog = null },
                confirmButton = { TextButton({ dialog = null }) { Text("إغلاق") } },
                title = { Text("الأشخاص") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("اضغط على اسم متصل لبدء مكالمة مباشرة", fontSize = 12.sp, color = Color.Gray)
                        if (peers.isEmpty() && saved.isEmpty())
                            Text("لا أحد بعد. اتصلوا بنفس الشبكة ونفس رمز الشبكة.", Modifier.padding(top = 8.dp))
                        peers.forEach { p ->
                            Text("● ${p.name}   (CH %02d)".format(p.channel), color = Color(0xFF1B8F4A), fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    dialog = null; Hub.radio?.startCall(p.uid)
                                }.padding(vertical = 10.dp))
                        }
                        saved.filterKeys { it !in onlineIds }.forEach { (_, n) ->
                            Text("○ $n  (غير متصل)", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            )
        }
        "network" -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton({ dialog = null }) { Text("إغلاق") } },
            title = { Text("الشبكة المحلية") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { createNetwork() }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء شبكة وعرض QR") }
                    Button(onClick = { scanQr() }, modifier = Modifier.fillMaxWidth()) { Text("مسح QR للانضمام") }
                    OutlinedButton(onClick = { NetHelper.leave(ctx); qrBitmap = null; toast("تمت المغادرة") }, modifier = Modifier.fillMaxWidth()) {
                        Text("مغادرة الشبكة / إيقاف الهوتسبوت")
                    }
                    qrBitmap?.let {
                        Image(it.asImageBitmap(), "QR", Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(qrInfo, fontSize = 12.sp)
                        Text("اطلب من الآخرين مسح هذا الرمز من «مسح QR للانضمام».", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        )
        "settings" -> SettingsDialog(prefs, onBattery = {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) toast("التطبيق مستثنى من توفير البطارية بالفعل")
            else ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        }) { dialog = null }
    }
}

@Composable
private fun RoundBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(84.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center) }
}

@Composable
private fun Tile(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(sub, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsDialog(prefs: Prefs, onBattery: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(prefs.name) }
    var code by remember { mutableStateOf(prefs.code) }
    var boost by remember { mutableFloatStateOf(prefs.boost) }
    var keep by remember { mutableStateOf(prefs.keepScreen) }
    var beep by remember { mutableStateOf(prefs.beep) }
    var vol by remember { mutableStateOf(prefs.volPtt) }
    var auto by remember { mutableStateOf(prefs.autoStart) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton({
                prefs.name = name; prefs.code = code; prefs.boost = boost; prefs.keepScreen = keep
                prefs.beep = beep; prefs.volPtt = vol; prefs.autoStart = auto
                Hub.radio?.let { it.name = prefs.name; it.code = prefs.code; it.boost = boost }
                onClose()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClose) { Text("إلغاء") } },
        title = { Text("الإعدادات") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسمك") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("رمز الشبكة (نفسه عند الجميع)") }, singleLine = true)
                Text("تعزيز الصوت: %.1fx".format(boost))
                Slider(boost, { boost = it }, valueRange = 1f..4f)
                SwitchRow("نغمات التنبيه", beep) { beep = it }
                SwitchRow("التحدث بأزرار الصوت", vol) { vol = it }
                SwitchRow("إبقاء الشاشة مضاءة", keep) { keep = it }
                SwitchRow("إشعار/تشغيل عند إقلاع الجهاز", auto) { auto = it }
                OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) { Text("استثناء من توفير البطارية") }
            }
        }
    )
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
