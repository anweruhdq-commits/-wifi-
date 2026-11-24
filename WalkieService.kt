package com.anwar.walkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow

data class CallInfo(val state: Int = 0, val peer: Int = 0, val name: String = "")

/** حالة مشتركة بين الخدمة والواجهة. */
object Hub {
    @Volatile var radio: Radio? = null
    val connected = MutableStateFlow(false)
    val peers = MutableStateFlow<List<Radio.Peer>>(emptyList())
    val call = MutableStateFlow(CallInfo())
    val talking = MutableStateFlow(false)

    fun ptt(on: Boolean) {
        val r = radio ?: return
        if (on && call.value.state != 0) return
        talking.value = on
        r.talk(on)
    }
}

class WalkieService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Hub.radio != null) return START_STICKY

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH_ID, "الاتصال", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_CALL, "المكالمات", NotificationManager.IMPORTANCE_HIGH))
        }
        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("لاسلكي الفريق")
            .setContentText("متصل — جاهز للاستقبال")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val prefs = Prefs(this)
        val radio = Radio(this, prefs.uid).apply {
            name = prefs.name
            code = prefs.code
            channel = prefs.channel
            boost = prefs.boost
            onPeers = { list ->
                Hub.peers.value = list
                val saved = prefs.contacts.toMutableMap()
                var changed = false
                list.forEach { if (saved[it.uid] != it.name) { saved[it.uid] = it.name; changed = true } }
                if (changed) prefs.contacts = saved
            }
            onCall = { state, peer ->
                val nm2 = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                Hub.call.value = CallInfo(state, peer, peerName(peer))
                if (state == 2) {
                    nm2.notify(2, NotificationCompat.Builder(this@WalkieService, CH_CALL)
                        .setSmallIcon(android.R.drawable.ic_menu_call)
                        .setContentTitle("مكالمة واردة")
                        .setContentText(peerName(peer))
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setFullScreenIntent(openApp(), true)
                        .setContentIntent(openApp())
                        .setAutoCancel(true).build())
                } else nm2.cancel(2)
            }
        }
        try {
            radio.start()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.radio = radio
        Hub.connected.value = true

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkie:cpu").apply { acquire() }
        return START_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        Hub.radio?.stop()
        Hub.radio = null
        Hub.connected.value = false
        Hub.peers.value = emptyList()
        Hub.call.value = CallInfo()
        Hub.talking.value = false
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CH_ID = "walkie_conn"
        const val CH_CALL = "walkie_call"
    }
}
