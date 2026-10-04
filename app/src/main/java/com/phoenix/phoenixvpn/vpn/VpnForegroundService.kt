package com.phoenix.phoenixvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoenix.phoenixvpn.MainActivity
import com.phoenix.phoenixvpn.R

class VpnForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phoenix_vpn_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.phoenix.phoenixvpn.vpn.START"
        const val ACTION_STOP = "com.phoenix.phoenixvpn.vpn.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                // "ရပ်ရန်" behaves as MANUAL-OFF: it sets the user override
                // (so auto-run never resurrects the tunnel) and brings the
                // tunnel down for real — not just killing the service.
                try {
                    WireGuardManager.getInstance(this).handleNotificationStop()
                } catch (_: Exception) {
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // System restarted the service after killing the process
            // (START_STICKY delivers a null intent). Re-enter the foreground
            // immediately; tunnel state is left to WireGuardManager.
            null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ဖီးနစ် VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN ချိတ်ဆက်နေစဉ် အသိပေးချက်"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VpnForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ဖီးနစ် VPN")
            .setContentText("VPN ချိတ်ဆက်နေပါသည်")
            .setSmallIcon(R.drawable.phoenix_vpn_icon)
            .setContentIntent(pendingIntent)
            .addAction(0, "ရပ်ရန်", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
