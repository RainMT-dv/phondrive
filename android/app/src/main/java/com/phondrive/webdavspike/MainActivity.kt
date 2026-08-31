package com.phondrive.webdavspike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            PhonDriveTheme {
                PhonDriveScreen()
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, 1001)
            } catch (_: Exception) {}
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun PhonDriveTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF4FC3F7),
        onPrimary = Color(0xFF003355),
        primaryContainer = Color(0xFF004A77),
        onPrimaryContainer = Color(0xFFC8E6FF),
        secondary = Color(0xFF80DEEA),
        onSecondary = Color(0xFF00363A),
        secondaryContainer = Color(0xFF004F54),
        onSecondaryContainer = Color(0xFFB2EBF2),
        tertiary = Color(0xFFA5D6A7),
        onTertiary = Color(0xFF003300),
        surface = Color(0xFF1A1C1E),
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF252830),
        onSurfaceVariant = Color(0xFFC4C6CF),
        error = Color(0xFFFFB4AB),
        outline = Color(0xFF8E9099),
    )
    MaterialTheme(colorScheme = darkColorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonDriveScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(WebDavService.isServerRunning) }
    var phoneIp by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var autoDetectedIp by remember { mutableStateOf<String?>(null) }

    val prefs = remember { context.getSharedPreferences("phondrive", 0) }
    val savedIp = prefs.getString("tailscale_ip", "") ?: ""

    LaunchedEffect(Unit) {
        phoneIp = savedIp
        autoDetectedIp = WebDavService.currentIp
        if (autoDetectedIp != null && phoneIp.isEmpty()) phoneIp = autoDetectedIp!!
        while (true) {
            delay(2000)
            val running = WebDavService.isServerRunning
            val ip = WebDavService.currentIp
            isRunning = running
            if (ip != null) autoDetectedIp = ip
            if (running && (ip != null || phoneIp.isNotEmpty())) {
                val displayIp = ip ?: phoneIp
                serverUrl = "http://$displayIp:${WebDavService.currentPort}"
            } else {
                serverUrl = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PhonDrive",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            StatusCard(isRunning, serverUrl)

            ServerToggleCard(
                isRunning = isRunning,
                phoneIp = phoneIp,
                onIpChange = { phoneIp = it },
                onToggle = {
                    val intent = Intent(context, WebDavService::class.java)
                    if (isRunning) {
                        intent.action = WebDavService.ACTION_STOP
                        context.startService(intent)
                        isRunning = false
                        serverUrl = ""
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            try {
                                val permIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(permIntent)
                            } catch (_: Exception) {}
                            return@ServerToggleCard
                        }
                        prefs.edit().putString("tailscale_ip", phoneIp.trim()).apply()
                        intent.action = WebDavService.ACTION_START
                        intent.putExtra(WebDavService.EXTRA_PORT, 8080)
                        ContextCompat.startForegroundService(context, intent)
                        isRunning = true
                    }
                }
            )

            InfoCard(
                title = "Credenciais",
                icon = Icons.Filled.Key,
                items = listOf(
                    "usuario" to "user",
                    "senha" to "pass"
                )
            )

            HelpCard()

            Spacer(Modifier.weight(1f))

            Text(
                text = "v1.0.0",
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun StatusCard(isRunning: Boolean, serverUrl: String) {
    val containerColor by animateColorAsState(
        if (isRunning) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "status"
    )
    val contentColor by animateColorAsState(
        if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "statusContent"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRunning) Color(0xFF66BB6A).copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) Color(0xFF66BB6A) else MaterialTheme.colorScheme.outline)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (isRunning) "Servidor Ativo" else "Servidor Inativo",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            if (isRunning && serverUrl.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = serverUrl,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ServerToggleCard(
    isRunning: Boolean,
    phoneIp: String,
    onIpChange: (String) -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = phoneIp,
                onValueChange = onIpChange,
                label = { Text("IP do Tailscale") },
                placeholder = { Text("100.x.x.x") },
                singleLine = true,
                enabled = !isRunning,
                leadingIcon = {
                    Icon(Icons.Outlined.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    contentColor = if (isRunning) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "Parar Servidor" else "Ligar Servidor",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
fun InfoCard(title: String, icon: ImageVector, items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            items.forEach { (label, value) ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("  $label: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                    Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun HelpCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Como usar", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            val steps = listOf(
                "1. Instale o PhonDrive-Tray.exe no PC",
                "2. Abra o app, digite o IP do Tailscale",
                "3. Toque em Ligar Servidor",
                "4. No PC, clique Mount na tray"
            )
            steps.forEach { step ->
                Text(step, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}
