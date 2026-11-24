package com.anwar.walkie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED || !Prefs(c).autoStart) return
        if (Build.VERSION.SDK_INT >= 34) {
            // أندرويد 14+ يمنع بدء خدمة الميكروفون من الإقلاع: نعرض إشعاراً للضغط عليه.
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("walkie_boot", "التشغيل", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(3, NotificationCompat.Builder(c, "walkie_boot")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("لاسلكي الفريق")
                .setContentText("اضغط لفتح التطبيق والاتصال")
                .setContentIntent(open).setAutoCancel(true).build())
        } else {
            ContextCompat.startForegroundService(c, Intent(c, WalkieService::class.java))
        }
    }
}
