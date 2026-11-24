package com.anwar.walkie

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** إنشاء شبكة محلية (هوتسبوت) والانضمام إليها عبر QR. صيغة الرمز: WALKIE;S:ssid;P:pass;C:code;; */
object NetHelper {
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    data class Join(val ssid: String, val pass: String, val code: String)

    fun parse(text: String): Join? {
        if (!text.startsWith("WALKIE;")) return null
        val m = text.split(";").filter { it.contains(":") }.associate { it.substringBefore(":") to it.substringAfter(":") }
        val s = m["S"] ?: return null
        return Join(s, m["P"] ?: "", m["C"] ?: "88888888")
    }

    fun startHotspot(ctx: Context, code: String, onReady: (ssid: String, qr: String) -> Unit, onFail: (String) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val ssid: String; val pass: String
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = c.ssid ?: ""; pass = c.passphrase ?: ""
                    } else {
                        @Suppress("DEPRECATION") val c = r.wifiConfiguration
                        ssid = c?.SSID ?: ""; pass = c?.preSharedKey ?: ""
                    }
                    onReady(ssid, "WALKIE;S:$ssid;P:$pass;C:$code;;")
                }
                override fun onFailed(reason: Int) = onFail("تعذر إنشاء الشبكة (رمز $reason). أوقف الهوتسبوت العادي وتأكد من تفعيل الموقع.")
            }, main)
        } catch (e: SecurityException) {
            onFail("الصلاحيات ناقصة (الموقع / الأجهزة القريبة)")
        } catch (e: Exception) {
            onFail("تعذر إنشاء الشبكة: ${e.message}")
        }
    }

    fun join(ctx: Context, j: Join, onOk: () -> Unit, onFail: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) { onFail("الانضمام عبر QR يتطلب أندرويد 10 أو أحدث؛ اتصل بالشبكة يدوياً"); return }
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        leave(ctx, keepHotspot = true)
        val spec = WifiNetworkSpecifier.Builder().setSsid(j.ssid).apply { if (j.pass.isNotEmpty()) setWpa2Passphrase(j.pass) }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { cm.bindProcessToNetwork(n); main.post(onOk) }
            override fun onUnavailable() { main.post { onFail("تعذر الانضمام إلى الشبكة ${j.ssid}") } }
            override fun onLost(n: Network) { cm.bindProcessToNetwork(null) }
        }
        netCb = cb
        try { cm.requestNetwork(req, cb, 30_000) } catch (e: Exception) { onFail("تعذر الانضمام: ${e.message}") }
    }

    fun leave(ctx: Context, keepHotspot: Boolean = false) {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        netCb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
        if (!keepHotspot) { try { reservation?.close() } catch (_: Exception) {}; reservation = null }
    }

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        return bmp
    }
}
