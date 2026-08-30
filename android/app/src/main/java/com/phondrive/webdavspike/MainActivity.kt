package com.phondrive.webdavspike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var ipInput: EditText
    private var isRunning = false
    private var serverIp: String? = null
    private var serverPort = 8080
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            syncServiceState()
            if (isRunning) handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        ipInput = EditText(this).apply {
            hint = "Tailscale IP (e.g. 100.84.246.7)"
            inputType = InputType.TYPE_CLASS_TEXT
            val prefs = getSharedPreferences("phondrive", MODE_PRIVATE)
            setText(prefs.getString("tailscale_ip", ""))
        }

        toggleButton = Button(this).apply {
            text = "Start Server"
            setOnClickListener { toggleServer() }
        }

        statusText = TextView(this).apply {
            text = "PhonDrive WebDAV Server"
            textSize = 16f
        }

        layout.addView(ipInput)
        layout.addView(toggleButton)
        layout.addView(statusText)

        val scrollView = ScrollView(this).apply { addView(layout) }
        setContentView(scrollView)

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
        if (isRunning) handler.postDelayed(pollRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    private fun syncServiceState() {
        val running = WebDavService.isServerRunning
        val detectedIp = WebDavService.currentIp
        val manualIp = ipInput.text.toString().trim().ifEmpty { null }
        val ip = detectedIp ?: manualIp
        val port = WebDavService.currentPort
        if (running != isRunning || ip != serverIp) {
            isRunning = running
            serverIp = ip
            serverPort = port
            runOnUiThread {
                if (running) {
                    toggleButton.text = "Stop Server"
                    val displayIp = ip ?: "(no IP - enter manually above)"
                    statusText.text = buildString {
                        append("Server running on port $serverPort\n\n")
                        append("Tailscale IP: $displayIp\n")
                        append("URL: http://$displayIp:$serverPort/\n\n")
                        append("Root: ${Environment.getExternalStorageDirectory().absolutePath}\n\n")
                        append("WebDAV endpoints:\n")
                        append("  PROPFIND / - list\n")
                        append("  GET /path - download\n")
                        append("  PUT /path - upload\n")
                        append("  DELETE /path - delete\n")
                        append("  MKCOL /path - mkdir\n")
                        append("  MOVE /path - rename\n")
                        append("  COPY /path - copy\n\n")
                        append("Auth: user / pass\n")
                        append("Test: curl -u user:pass http://$displayIp:$serverPort/ping")
                    }
                } else {
                    toggleButton.text = "Start Server"
                    statusText.text = "Server stopped."
                }
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
            }
        }
        requestStoragePermission()
        requestBatteryOptimization()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, 1001)
            }
        } else {
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
            } catch (_: Exception) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                appendStatus("Storage permission granted!")
            } else {
                appendStatus("Storage permission DENIED.")
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
                    appendStatus("Storage permission DENIED.")
                }
            }
            1003 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    appendStatus("Notification permission granted!")
                } else {
                    appendStatus("Notification permission denied.")
                }
            }
        }
    }

    private fun toggleServer() {
        val intent = Intent(this, WebDavService::class.java)
        if (isRunning) {
            intent.action = WebDavService.ACTION_STOP
            startService(intent)
            isRunning = false
            toggleButton.text = "Start Server"
            statusText.text = "Server stopped."
            handler.removeCallbacks(pollRunnable)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                appendStatus("ERROR: Storage permission not granted!")
                requestStoragePermission()
                return
            }
            getSharedPreferences("phondrive", MODE_PRIVATE).edit()
                .putString("tailscale_ip", ipInput.text.toString().trim())
                .apply()
            intent.action = WebDavService.ACTION_START
            intent.putExtra(WebDavService.EXTRA_PORT, 8080)
            ContextCompat.startForegroundService(this, intent)
            handler.postDelayed(pollRunnable, 1000)
        }
    }

    private fun appendStatus(msg: String) {
        runOnUiThread { statusText.append("\n$msg") }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }
}
