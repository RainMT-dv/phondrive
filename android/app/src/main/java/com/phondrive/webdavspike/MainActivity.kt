package com.phondrive.webdavspike

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private var isRunning = false
    private var serverIp: String? = null
    private var serverPort = 8080

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra(WebDavService.EXTRA_IS_RUNNING, false) ?: false
            val ip = intent?.getStringExtra(WebDavService.EXTRA_IP)
            val port = intent?.getIntExtra(WebDavService.EXTRA_PORT_INT, 8080) ?: 8080
            val error = intent?.getStringExtra(WebDavService.EXTRA_ERROR)

            isRunning = running
            serverIp = ip
            serverPort = port

            runOnUiThread {
                if (running) {
                    toggleButton.text = "Stop Server"
                    statusText.text = buildString {
                        append("Server running on port $port\n\n")
                        append("Tailscale IP: ${ip ?: "not found"}\n")
                        append("URL: http://${ip ?: "?"}:$port/\n\n")
                        append("Root: ${Environment.getExternalStorageDirectory().absolutePath}\n\n")
                        append("WebDAV endpoints:\n")
                        append("  PROPFIND / — list files\n")
                        append("  GET /path — download file\n")
                        append("  PUT /path — upload file\n")
                        append("  DELETE /path — delete file\n")
                        append("  MKCOL /path — create folder\n")
                        append("  MOVE /path — rename/move\n")
                        append("  COPY /path — copy file\n\n")
                        append("Auth: user / pass\n")
                        append("Test: curl -u user:pass http://$ip:$port/ping")
                    }
                } else {
                    toggleButton.text = "Start Server"
                    statusText.text = if (error != null) {
                        "Error: $error"
                    } else {
                        "Server stopped."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "PhonDrive WebDAV Server"
            textSize = 16f
        }

        toggleButton = Button(this).apply {
            text = "Start Server"
            setOnClickListener { toggleServer() }
        }

        layout.addView(toggleButton)
        layout.addView(statusText)

        val scrollView = ScrollView(this).apply { addView(layout) }
        setContentView(scrollView)

        // Request permissions
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(WebDavService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun requestPermissions() {
        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1003
                )
            }
        }

        // Request storage permission
        requestStoragePermission()

        // Request battery optimization exemption
        requestBatteryOptimization()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, 1001)
            }
        } else {
            // Android 10 and below
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1002)
            }
        }
    }

    private fun requestBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Some devices don't support this intent
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                appendStatus("Storage permission granted!")
            } else {
                appendStatus("Storage permission DENIED. Server cannot start.")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    appendStatus("Storage permission granted!")
                } else {
                    appendStatus("Storage permission DENIED. Server cannot start.")
                }
            }
            1003 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    appendStatus("Notification permission granted!")
                } else {
                    appendStatus("Notification permission denied. Service notification may not show.")
                }
            }
        }
    }

    private fun toggleServer() {
        val intent = Intent(this, WebDavService::class.java)
        if (isRunning) {
            intent.action = WebDavService.ACTION_STOP
            startService(intent)
        } else {
            // Check storage permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                appendStatus("ERROR: Storage permission not granted!")
                requestStoragePermission()
                return
            }
            intent.action = WebDavService.ACTION_START
            intent.putExtra(WebDavService.EXTRA_PORT, 8080)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun appendStatus(msg: String) {
        runOnUiThread {
            statusText.append("\n$msg")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
