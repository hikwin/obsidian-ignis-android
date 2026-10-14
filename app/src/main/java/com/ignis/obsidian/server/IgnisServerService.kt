package com.ignis.obsidian.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ignis.obsidian.MainActivity
import com.ignis.obsidian.R
import com.ignis.obsidian.util.NetworkUtils

class IgnisServerService : Service() {

    companion object {
        const val ACTION_START = "com.ignis.obsidian.ACTION_START"
        const val ACTION_STOP = "com.ignis.obsidian.ACTION_STOP"
        const val CHANNEL_ID = "ignis_server_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning: Boolean = false
            private set
        var currentPort: Int = 8080
            private set
        var currentUrl: String = ""
            private set
        var currentAccessCode: String = ""
            private set

        var onStateChangeListener: ((Boolean, String) -> Unit)? = null

        fun startService(context: Context, port: Int = 8080, accessCode: String = "") {
            val intent = Intent(context, IgnisServerService::class.java).apply {
                action = ACTION_START
                putExtra("port", port)
                putExtra("access_code", accessCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, IgnisServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: IgnisHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopServer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val accessCode = intent?.getStringExtra("access_code")
            ?: getSharedPreferences("ignis_settings", Context.MODE_PRIVATE).getString("server_access_code", "") ?: ""
        startServer(port, accessCode)

        return START_STICKY
    }

    private fun startServer(port: Int, accessCode: String = "") {
        if (isRunning) return

        try {
            currentPort = port
            currentAccessCode = accessCode
            server = IgnisHttpServer(this, port, accessCode).apply {
                start()
            }
            isRunning = true

            val ip = NetworkUtils.getLocalIpAddress()
            currentUrl = "http://$ip:$port"

            startForeground(NOTIFICATION_ID, buildNotification(currentUrl))
            onStateChangeListener?.invoke(true, currentUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            currentUrl = ""
            currentAccessCode = ""
            onStateChangeListener?.invoke(false, "")
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        isRunning = false
        currentUrl = ""
        currentAccessCode = ""
        onStateChangeListener?.invoke(false, "")
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBServer Web 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 OBServer 本地 Web 服务在后台正常运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(url: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, IgnisServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBServer Web 服务运行中")
            .setContentText("访问地址: $url")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "停止服务", pendingStop)
            .setOngoing(true)
            .build()
    }
}
