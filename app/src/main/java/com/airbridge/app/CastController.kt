package com.airbridge.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.MdnsDiscoverer
import com.airbridge.app.airplay.RaopSender
import com.airbridge.app.airplay.RaopSender.LatencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** App-wide state shared between the UI and the casting service. */
@SuppressLint("StaticFieldLeak") // stores only applicationContext
object CastController {

    /** 一路已连接的 AirPlay 会话（一个设备一路）。 */
    data class SessionEntry(
        val device: AirplayDevice,
        val status: String = "正在连接...",
        val streaming: Boolean = false
    )

    data class UiState(
        val discovering: Boolean = false,
        val devices: List<AirplayDevice> = emptyList(),
        val testToneOn: Boolean = false,
        val latencyMode: LatencyMode = LatencyMode.STABLE,
        val robustMode: Boolean = false,
        val alacOn: Boolean = true,
        val status: String = "未连接",
        val logs: List<String> = emptyList(),
        val volume: Float = 25f,
        val mutePhone: Boolean = false,
        val sessions: List<SessionEntry> = emptyList()
    ) {
        /** 是否至少有一路正在推流。 */
        val streaming: Boolean get() = sessions.any { it.streaming }
        val connectedCount: Int get() = sessions.size
        /** 已连接设备 key 集合（host:port），供 UI 判断按钮状态。 */
        val connectedKeys: Set<String> get() = sessions.map { keyOf(it.device) }.toSet()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var discoverer: MdnsDiscoverer? = null
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 每个已连设备的 RaopSender，key = host:port。
     * 这是唯一的权威集合：CastService 的音频扇出、全局音量广播都读它。
     * internal 可见性，供同模块的 CastService 直接遍历。
     */
    internal val senders = ConcurrentHashMap<String, RaopSender>()

    private const val PREFS = "airbridge_prefs"
    private const val KEY_MANUAL = "manual_devices"
    private const val KEY_LATENCY = "latency_mode"
    private const val KEY_ROBUST = "robust_mode"
    private const val KEY_ALAC = "alac_on"
    private const val KEY_VOLUME = "volume"

    fun keyOf(device: AirplayDevice): String = "${device.host}:${device.port}"

    fun anyConnected(): Boolean = senders.isNotEmpty()

    fun senderFor(device: AirplayDevice): RaopSender? = senders[keyOf(device)]

    // ------------------------------------------------------------------
    // 发现
    // ------------------------------------------------------------------

    fun startDiscovery(context: Context) {
        if (discoverer != null) return
        _state.update { it.copy(discovering = true, logs = it.logs + "正在扫描局域网 AirPlay 设备...") }
        discoverer = MdnsDiscoverer(
            context = context,
            listener = { devices ->
                val prev = _state.value.devices.map { it.host to it.port }
                _state.update { it.copy(devices = devices.sortedBy { d -> d.name }) }
                val now = _state.value.devices
                for (d in now) {
                    if ((d.host to d.port) !in prev) {
                        addLog("发现设备: ${d.name} (${d.host}:${d.port}, ${d.model ?: "AirPlay"})")
                    }
                }
            },
            log = { addLog(it) }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        discoverer?.stop()
        discoverer = null
        _state.update { it.copy(discovering = false) }
    }

    fun addManual(device: AirplayDevice) {
        _state.update {
            it.copy(
                devices = (it.devices + device).distinctBy { d -> d.host to d.port },
                logs = it.logs + "已手动添加 ${device.name} (${device.host}:${device.port})"
            )
        }
        saveManual(contextForPrefs ?: return)
    }

    fun removeManual(device: AirplayDevice) {
        _state.update {
            it.copy(
                devices = it.devices.filterNot { d -> d.host == device.host && d.port == device.port },
                logs = it.logs + "已删除 ${device.name} (${device.host}:${device.port})"
            )
        }
        saveManual(contextForPrefs ?: return)
    }

    private var contextForPrefs: Context? = null

    fun loadManual(context: Context) {
        contextForPrefs = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_LATENCY, LatencyMode.STABLE.name) ?: LatencyMode.STABLE.name
        val mode = runCatching { LatencyMode.valueOf(modeName) }.getOrDefault(LatencyMode.STABLE)
        val robust = prefs.getBoolean(KEY_ROBUST, false)
        val alacOn = prefs.getBoolean(KEY_ALAC, true)
        val volume = prefs.getFloat(KEY_VOLUME, 25f).coerceIn(0f, 100f)
        _state.update { it.copy(latencyMode = mode, robustMode = robust, alacOn = alacOn, volume = volume) }
        val raw = prefs.getString(KEY_MANUAL, "") ?: ""
        for (line in raw.split("\n")) {
            val parts = line.split("|")
            if (parts.size == 3) {
                val name = parts[0]
                val host = parts[1]
                val port = parts[2].toIntOrNull() ?: 7000
                if (host.isNotBlank()) {
                    val dev = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")
                    _state.update {
                        it.copy(devices = (it.devices + dev).distinctBy { d -> d.host to d.port })
                    }
                }
            }
        }
    }

    private fun saveManual(context: Context) {
        val lines = _state.value.devices
            .filter { it.type == "_manual" || it.txt.isEmpty() }
            .distinctBy { it.host to it.port }
            .joinToString("\n") { "${it.name}|${it.host}|${it.port}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANUAL, lines)
            .apply()
    }

    // ------------------------------------------------------------------
    // 全局设置（应用到所有已连/后续设备）
    // ------------------------------------------------------------------

    fun setLatencyMode(mode: LatencyMode) {
        _state.update { it.copy(latencyMode = mode) }
        senders.values.forEach { it.latencyMode = mode }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LATENCY, mode.name)
            ?.apply()
        addLog("延迟模式: ${mode.name}")
    }

    fun setRobustMode(on: Boolean) {
        _state.update { it.copy(robustMode = on) }
        senders.values.forEach { it.robustMode = on }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ROBUST, on)
            ?.apply()
        addLog(if (on) "已开启网络稳健模式（冗余发包）" else "已关闭网络稳健模式")
    }

    fun setAlac(on: Boolean) {
        _state.update { it.copy(alacOn = on) }
        senders.values.forEach { it.alacOn = on }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ALAC, on)
            ?.apply()
        addLog(if (on) "已开启压缩 ALAC（减小音频包）" else "已关闭压缩 ALAC（使用未压缩帧）")
    }

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    fun addSession(device: AirplayDevice) {
        val key = keyOf(device)
        _state.update {
            val without = it.sessions.filterNot { s -> keyOf(s.device) == key }
            it.copy(
                sessions = without + SessionEntry(device, status = "正在连接 ${device.name}...", streaming = false),
                status = if (it.sessions.isEmpty()) "正在连接 ${device.name}..." else it.status
            )
        }
    }

    fun markSessionConnecting(device: AirplayDevice, text: String) {
        val key = keyOf(device)
        _state.update {
            it.copy(
                sessions = it.sessions.map { s -> if (keyOf(s.device) == key) s.copy(status = text) else s }
            )
        }
    }

    /** 注册 sender 到权威集合（握手期间也参与扇出，保证一上线就有数据）。 */
    fun addSender(device: AirplayDevice, s: RaopSender) {
        senders[keyOf(device)] = s
    }

    fun setSessionStreaming(device: AirplayDevice, s: RaopSender) {
        senders[keyOf(device)] = s
        _state.update {
            it.copy(
                sessions = it.sessions.map { se ->
                    if (keyOf(se.device) == keyOf(device)) se.copy(status = "正在投射 ${device.name}", streaming = true) else se
                },
                status = buildStatus(it.sessions.size)
            )
        }
    }

    fun setSessionStatus(device: AirplayDevice, text: String) {
        val key = keyOf(device)
        _state.update {
            it.copy(
                sessions = it.sessions.map { s -> if (keyOf(s.device) == key) s.copy(status = text) else s }
            )
        }
    }

    /**
     * 移除一路会话（从状态与 sender 集合中删除）。
     * 注意：调用方负责先 stop 该 sender；这里只清理集合与状态。
     * 幂等：重复调用（如 sender 自身停止回调触发的再入）不会重复写日志。
     */
    fun removeSession(device: AirplayDevice) {
        val key = keyOf(device)
        val wasPresent = senders.containsKey(key) ||
            _state.value.sessions.any { keyOf(it.device) == key }
        senders.remove(key)
        _state.update {
            val sessions = it.sessions.filterNot { s2 -> keyOf(s2.device) == key }
            it.copy(
                sessions = sessions,
                status = buildStatus(sessions.size),
                logs = if (wasPresent) it.logs + "已断开 ${device.name}" else it.logs
            )
        }
    }

    private fun buildStatus(count: Int): String = when {
        count <= 0 -> "未连接"
        count == 1 -> "正在投射 1 台设备"
        else -> "正在投射 $count 台设备"
    }

    fun setTestTone(on: Boolean) {
        _state.update { it.copy(testToneOn = on) }
    }

    fun setStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    fun addLog(line: String) {
        Log.d("AirBridge", line)
        val ctx = contextForPrefs
        if (ctx != null) {
            logExecutor.execute {
                try {
                    val f = java.io.File(ctx.filesDir, "airbridge.log")
                    if (f.length() > 256L * 1024) f.delete()
                    f.appendText("${System.currentTimeMillis()} $line\n")
                } catch (_: Exception) {
                }
            }
        }
        _state.update { it.copy(logs = (it.logs + line).takeLast(300)) }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun setVolume(pct: Float) {
        val v = pct.coerceIn(0f, 100f)
        _state.update { it.copy(volume = v) }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_VOLUME, v)
            ?.apply()
        // 全局音量：广播到所有已连设备
        senders.values.forEach { it.setVolume(v.toDouble()) }
    }

    /**
     * 静音本机：把手机媒体音量调为 0（等效侧键静音），避免手机与音箱同时出声。
     * 仅控制设备媒体音量；若本机捕获绑定设备音量（部分机型），HomePod 也会跟着静音，
     * 此时需用户手动调高手机音量、本项目无法单独静音本机。
     */
    fun setMutePhone(context: Context, on: Boolean) {
        _state.update { it.copy(mutePhone = on) }
        // 通知服务立即生效（服务未运行时仅记录状态，下次连接时应用）
        context.startService(
            Intent(context, CastService::class.java)
                .setAction(CastService.ACTION_SET_MUTE_PHONE)
                .putExtra(CastService.EXTRA_MUTE, on)
        )
        addLog(if (on) "已静音本机（手机扬声器）" else "已取消静音本机")
    }

    // ------------------------------------------------------------------
    // 服务指令
    // ------------------------------------------------------------------

    fun startFirst(context: Context, resultCode: Int, data: Intent, device: AirplayDevice) {
        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CastService.EXTRA_DATA, data)
            .putExtra(CastService.EXTRA_HOST, device.host)
            .putExtra(CastService.EXTRA_PORT, device.port)
            .putExtra(CastService.EXTRA_NAME, device.name)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    /** 已存在投射会话时，直接把新设备加入（无需再次授权）。 */
    fun addDevice(context: Context, device: AirplayDevice) {
        if (senderFor(device) != null) {
            addLog("${device.name} 已经连接")
            return
        }
        addSession(device)
        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_ADD_DEVICE)
            .putExtra(CastService.EXTRA_HOST, device.host)
            .putExtra(CastService.EXTRA_PORT, device.port)
            .putExtra(CastService.EXTRA_NAME, device.name)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    /** 断开单个设备。 */
    fun removeDevice(context: Context, device: AirplayDevice) {
        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_REMOVE_DEVICE)
            .putExtra(CastService.EXTRA_HOST, device.host)
            .putExtra(CastService.EXTRA_PORT, device.port)
            .putExtra(CastService.EXTRA_NAME, device.name)
        context.startService(intent)
    }

    fun stop(context: Context) {
        // Must deliver ACTION_STOP so the service runs its cleanup path.
        context.startService(
            Intent(context, CastService::class.java).setAction(CastService.ACTION_STOP)
        )
    }

    fun toggleTestTone(context: Context) {
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_TEST_TONE))
    }
}
