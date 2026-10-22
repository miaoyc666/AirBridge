package com.airbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.RaopSender.LatencyMode

class MainActivity : ComponentActivity() {

    private companion object {
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private var pendingDevice: AirplayDevice? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val device = pendingDevice ?: return@registerForActivityResult
            if (result.resultCode == RESULT_OK && result.data != null) {
                CastController.startFirst(this, result.resultCode, result.data!!, device)
            } else {
                CastController.addLog("未获得音频捕获授权")
            }
            pendingDevice = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val perms = ArrayList<String>()
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val localNetworkExists = try {
                packageManager.getPermissionInfo(PERM_LOCAL_NETWORK, 0)
                true
            } catch (e: Exception) {
                false
            }
            if (localNetworkExists &&
                checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(PERM_LOCAL_NETWORK)
            }
            if (perms.isNotEmpty()) {
                requestPermissions(perms.toTypedArray(), 100)
            }
        }
        CastController.loadManual(applicationContext)
        CastController.startDiscovery(applicationContext)
        setContent {
            App(
                onConnect = { device -> connect(device) },
                onDisconnect = { device -> CastController.removeDevice(this, device) }
            )
        }
    }

    private fun connect(device: AirplayDevice) {
        // 已存在投射会话：直接把新设备加入，无需再次授权音频捕获
        if (CastController.anyConnected()) {
            CastController.addDevice(this, device)
            return
        }
        // 首次连接：申请音频捕获授权（Android 14+ 使用仅音频模式，不再弹“屏幕录制”）
        pendingDevice = device
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mpm.createScreenCaptureIntent()
        if (Build.VERSION.SDK_INT >= 34) {
            // 仅音频捕获：AOSP 常量 EXTRA_CAPTURE_AUDIO_ONLY 的字符串值（部分 SDK 未导出该符号）
            captureIntent.putExtra("android.media.projection.extra.EXTRA_CAPTURE_AUDIO_ONLY", true)
        }
        projectionLauncher.launch(captureIntent)
    }

    override fun onDestroy() {
        if (isFinishing) CastController.stopDiscovery()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    onConnect: (AirplayDevice) -> Unit,
    onDisconnect: (AirplayDevice) -> Unit
) {
    val ui by CastController.state.collectAsState()
    val context = LocalContext.current
    var showManual by remember { mutableStateOf(false) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("AirBridge") }) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showManual = true },
                    icon = { Text("+") },
                    text = { Text("手动添加") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(ui)
                SettingsCard(ui)
                if (ui.sessions.isNotEmpty()) {
                    ConnectedCard(
                        ui = ui,
                        onVolume = { CastController.setVolume(it) },
                        onMutePhone = { CastController.setMutePhone(context, it) },
                        onTestTone = { CastController.toggleTestTone(context) },
                        onDisconnect = onDisconnect,
                        onStopAll = { CastController.stop(context) }
                    )
                }
                DeviceList(
                    ui = ui,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onDelete = { CastController.removeManual(it) },
                    modifier = Modifier.weight(1f)
                )
                LogPanel(ui.logs, modifier = Modifier.height(140.dp))
            }
        }
    }

    if (showManual) {
        ManualDialog(
            onDismiss = { showManual = false },
            onAdd = { name, ip, port ->
                CastController.addManual(AirplayDevice(name = name, host = ip, port = port))
                showManual = false
            }
        )
    }
}

@Composable
private fun SettingsCard(ui: CastController.UiState) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("延迟与网络", style = MaterialTheme.typography.titleMedium)
            Text("延迟模式（下次连接生效）", style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                LatencyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = ui.latencyMode == mode,
                        onClick = { CastController.setLatencyMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    LatencyMode.STABLE -> "稳定"
                                    LatencyMode.LOW -> "低延迟"
                                    LatencyMode.ULTRA -> "极低延迟"
                                    LatencyMode.SAFE -> "网络稳定"
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }
            Text(
                "网络稳定≈2s · 稳定≈1.5s · 低延迟≈0.25s · 极低延迟≈0.1s（弱网推荐网络稳定）",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("网络稳健模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Wi-Fi 信号不佳时冗余发包，降低爆音",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = ui.robustMode,
                    onCheckedChange = { CastController.setRobustMode(it) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("压缩 ALAC", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "减小每个音频包，弱网更稳；出现异常时请关闭",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = ui.alacOn,
                    onCheckedChange = { CastController.setAlac(it) }
                )
            }
        }
    }
}

@Composable
private fun StatusCard(ui: CastController.UiState) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(ui.status, style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.discovering) "正在扫描局域网... 已发现 ${ui.devices.size} 个设备" else "设备数: ${ui.devices.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (ui.sessions.any { !it.streaming }) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConnectedCard(
    ui: CastController.UiState,
    onVolume: (Float) -> Unit,
    onMutePhone: (Boolean) -> Unit,
    onTestTone: () -> Unit,
    onDisconnect: (AirplayDevice) -> Unit,
    onStopAll: () -> Unit
) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("已连接 ${ui.connectedCount} 台设备", style = MaterialTheme.typography.titleMedium)
            // 每路设备状态 + 断开
            ui.sessions.forEach { session ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.device.name, style = MaterialTheme.typography.titleSmall)
                        Text(session.status, style = MaterialTheme.typography.bodySmall)
                        Text("${session.device.host}:${session.device.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onDisconnect(session.device) }) {
                        Text("断开")
                    }
                }
            }
            Button(onClick = onTestTone) {
                Text(if (ui.testToneOn) "停止测试音" else "播放测试音 (440Hz)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("静音本机", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "投射时把手机媒体音量调为 0（等效侧键静音，可随时切回）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = ui.mutePhone, onCheckedChange = onMutePhone)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量")
                Slider(
                    value = ui.volume,
                    onValueChange = onVolume,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    valueRange = 0f..100f
                )
                Text("${ui.volume.toInt()}%")
            }
            Button(
                onClick = onStopAll,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("断开全部")
            }
        }
    }
}

@Composable
private fun DeviceList(
    ui: CastController.UiState,
    onConnect: (AirplayDevice) -> Unit,
    onDisconnect: (AirplayDevice) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (AirplayDevice) -> Unit = {}
) {
    if (ui.devices.isEmpty()) {
        Text(
            "没有发现设备。\n请确认手机和 HomePod 连接的是同一个 Wi-Fi；\n如果自动发现不到，点右下角“手动添加”，输入 HomePod 的 IP（端口 7000）。"
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(ui.devices, key = { "${it.type}|${it.name}|${it.host}:${it.port}" }) { d ->
            val connected = ui.connectedKeys.contains("${d.host}:${d.port}")
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(d.model ?: "AirPlay", style = MaterialTheme.typography.bodySmall)
                        Text("${d.host}:${d.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (connected) {
                        TextButton(onClick = { onDisconnect(d) }) {
                            Text("断开")
                        }
                    } else {
                        Button(onClick = { onConnect(d) }) {
                            Text(if (ui.connectedCount > 0) "添加" else "连接")
                        }
                    }
                    if (d.txt.isEmpty()) {
                        TextButton(onClick = { onDelete(d) }) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(logs) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("日志", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { CastController.clearLogs() }) {
                    Text("清空")
                }
            }
            Text(
                text = logs.takeLast(40).joinToString("\n").ifEmpty { "日志区" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(bottom = 6.dp)
            )
        }
    }
}

@Composable
private fun ManualDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, ip: String, port: Int) -> Unit
) {
    var name by remember { mutableStateOf("HomePod") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP 地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 7000
                    if (ip.isNotBlank()) onAdd(name.ifBlank { ip }, ip.trim(), p)
                },
                enabled = ip.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } ?: false)
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
