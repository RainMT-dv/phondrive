package com.phondrive.webdavspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class WebDavService : Service() {

    companion object {
        const val CHANNEL_ID = "phondrive_webdav"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.phondrive.webdavspike.START"
        const val ACTION_STOP = "com.phondrive.webdavspike.STOP"
        const val EXTRA_PORT = "port"

        // Status broadcast
        const val ACTION_STATUS = "com.phondrive.webdavspike.STATUS"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT_INT = "port"
        const val EXTRA_ERROR = "error"
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                startServer(port)
            }
            ACTION_STOP -> {
                stopServer()
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            broadcastStatus(running = false, error = "Cannot read external storage")
            stopSelf()
            return
        }

        val webDav = WebDavServer(rootDir)

        scope.launch {
            try {
                server = embeddedServer(CIO, port = port) {
                    routing {
                        webDav.install(this)
                    }
                }.start(wait = false)

                acquireWakeLock()
                val ip = getDeviceTailscaleIp()

                // Post foreground notification
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(ip, port),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )

                broadcastStatus(running = true, ip = ip, port = port)
            } catch (e: Exception) {
                broadcastStatus(running = false, error = e.message ?: "Unknown error")
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        releaseWakeLock()
        broadcastStatus(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhonDrive WebDAV Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while WebDAV server is running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String?, port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, WebDavService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhonDrive WebDAV Server")
            .setContentText("Running on http://${ip ?: "?"}:$port")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun getDeviceTailscaleIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        // Tailscale uses 100.64.0.0/10 (CGNAT range)
                        if (ip.startsWith("100.") && ip.substringAfter(".").toIntOrNull()?.let { it in 64..127 } == true) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun broadcastStatus(running: Boolean, ip: String? = null, port: Int = 8080, error: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_IS_RUNNING, running)
            putExtra(EXTRA_IP, ip)
            putExtra(EXTRA_PORT_INT, port)
            putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhonDrive:WebDavServerLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 2000)
        scope.cancel()
        releaseWakeLock()
    }
}
