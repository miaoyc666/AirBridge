package com.airbridge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.RaopSender
import com.airbridge.app.audio.AudioCapture

class CastService : Service() {

    companion object {
        const val ACTION_START = "com.airbridge.app.action.START"
        const val ACTION_STOP = "com.airbridge.app.action.STOP"
        const val ACTION_ADD_DEVICE = "com.airbridge.app.action.ADD_DEVICE"
        const val ACTION_REMOVE_DEVICE = "com.airbridge.app.action.REMOVE_DEVICE"
        const val ACTION_TEST_TONE = "com.airbridge.app.action.TEST_TONE"
        const val ACTION_SET_MUTE_PHONE = "com.airbridge.app.action.SET_MUTE_PHONE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_MUTE = "mute"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1
    }

    /** 捕获授权（MediaProjection）仅创建一次，所有设备共用同一路系统音频。 */
    private var projection: MediaProjection? = null
    private var capture: AudioCapture? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var testToneOn = false
    private var testToneThread: Thread? = null

    /** 静音本机：保存静音前的媒体音量，断开时还原。 */
    private var savedMusicVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleCommand(intent)
        return START_NOT_STICKY
    }

    private fun handleCommand(intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_TEST_TONE -> toggleTestTone()
            ACTION_SET_MUTE_PHONE -> applyMutePhone(intent?.getBooleanExtra(EXTRA_MUTE, false) ?: false)
            ACTION_ADD_DEVICE -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return
                val port = intent.getIntExtra(EXTRA_PORT, 7000)
                val name = intent.getStringExtra(EXTRA_NAME) ?: host
                val device = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")
                addDevice(device)
            }
            ACTION_REMOVE_DEVICE -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return
                val port = intent.getIntExtra(EXTRA_PORT, 7000)
                val name = intent.getStringExtra(EXTRA_NAME) ?: host
                val device = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")
                removeDevice(device)
            }
            ACTION_START -> startCasting(intent)
        }
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 首次连接：创建投影 + 捕获，并接入第一个设备
    // ------------------------------------------------------------------

    private fun startCasting(intent: Intent) {
        if (stopping) return
        stopping = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        } ?: run {
            stopSelf()
            return
        }
        val host = intent.getStringExtra(EXTRA_HOST) ?: run {
            stopSelf()
            return
        }
        val port = intent.getIntExtra(EXTRA_PORT, 7000)
        val name = intent.getStringExtra(EXTRA_NAME) ?: host
        val device = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")

        startForegroundCompat()
        CastController.addSession(device)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: run {
            CastController.addLog("无法创建音频捕获会话")
            CastController.removeSession(device)
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    CastController.addLog("音频捕获授权被系统撤销")
                    stopAll()
                }
            },
            Handler(Looper.getMainLooper())
        )

        val cap = AudioCapture(
            proj,
            // 捕获路只有一条：把 PCM 扇出（fan-out）给所有已连设备的 sender
            onPcm = { samples, rate ->
                for (s in CastController.senders.values) s.pushPcm(samples, rate)
            },
            onError = { msg -> CastController.addLog(msg) },
            onLog = { msg -> CastController.addLog(msg) }
        )
        cap.onRate = { rate -> for (s in CastController.senders.values) s.onCaptureRate(rate) }
        capture = cap
        if (!cap.start()) {
            CastController.addLog("无法捕获系统音频（请检查是否允许捕获音频）")
            CastController.removeSession(device)
            cleanupProjection()
            stopSelf()
            return
        }
        acquireNetworkLocks()

        // 按当前开关状态应用“静音本机”
        applyMutePhone(CastController.state.value.mutePhone)

        // 接入第一个设备
        createSender(device)
    }

    // ------------------------------------------------------------------
    // 新增设备（复用已有的投影与捕获）
    // ------------------------------------------------------------------

    private fun addDevice(device: AirplayDevice) {
        if (stopping) return
        if (CastController.senderFor(device) != null) {
            CastController.addLog("${device.name} 已在连接中")
            return
        }
        if (projection == null || capture == null) {
            CastController.addLog("音频捕获尚未就绪，无法连接 ${device.name}")
            CastController.removeSession(device)
            return
        }
        CastController.addSession(device)
        createSender(device)
    }

    private fun createSender(device: AirplayDevice) {
        // senderRef 用可变变量承接，避免匿名 Listener 在 s 赋值前引用它
        var senderRef: RaopSender? = null
        val listener = object : RaopSender.Listener {
            override fun onState(state: RaopSender.State) {
                if (senderRef !== CastController.senderFor(device)) return
                when (state) {
                    RaopSender.State.PAIRING -> CastController.markSessionConnecting(device, "正在配对 ${device.name}...")
                    RaopSender.State.HANDSHAKE -> CastController.markSessionConnecting(device, "正在建立会话 ${device.name}...")
                    else -> Unit
                }
            }

            override fun onLog(message: String) = CastController.addLog("[${device.name}] $message")

            override fun onStarted() {
                val s = senderRef ?: return
                if (s !== CastController.senderFor(device)) return
                CastController.setSessionStreaming(device, s)
                s.setVolume(CastController.state.value.volume.toDouble())
            }

            override fun onStopped(reason: String?) {
                // 单个设备停止：仅移除该路，全部断开才释放投影
                removeDevice(device)
            }
        }
        val s = RaopSender(device, listener)
        senderRef = s
        s.latencyMode = CastController.state.value.latencyMode
        s.robustMode = CastController.state.value.robustMode
        s.alacOn = CastController.state.value.alacOn
        CastController.addSender(device, s)
        s.start()
    }

    // ------------------------------------------------------------------
    // 断开单个设备
    // ------------------------------------------------------------------

    private fun removeDevice(device: AirplayDevice) {
        val s = CastController.senders.remove(CastController.keyOf(device))
        try {
            s?.stop()
        } catch (_: Exception) {
        }
        CastController.removeSession(device)
        if (CastController.senders.isEmpty()) {
            // 没有设备了，释放投影并结束服务
            stopCasting()
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 停止所有
    // ------------------------------------------------------------------

    private fun stopAll() {
        stopCasting()
    }

    private fun stopCasting() {
        if (stopping) return
        stopping = true
        CastController.addLog("正在停止投射...")
        stopTestTone()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cleanup() {
        stopTestTone()
        for (s in CastController.senders.values) {
            try {
                s.stop()
            } catch (_: Exception) {
            }
        }
        CastController.senders.clear()
        capture?.stop()
        capture = null
        releaseNetworkLocks()
        // 还原本机媒体音量（若之前被静音）
        applyMutePhone(false)
        cleanupProjection()
    }

    /**
     * 静音本机：把手机媒体（STREAM_MUSIC）音量调为 0，等效于按侧键静音。
     * 注意：AudioPlaybackCapture 的捕获在很多机型发生在 stream 音量之后，
     * 因此该操作可能同时让 HomePod 静音——这是平台限制，本项目无法单独静音本机。
     * 取消静音时还原静音前保存的音量。
     */
    private fun applyMutePhone(mute: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (mute) {
            if (savedMusicVolume < 0) {
                savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else if (savedMusicVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            savedMusicVolume = -1
        }
    }

    private fun cleanupProjection() {
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    private fun acquireNetworkLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBridge:cast").apply {
                setReferenceCounted(false)
                acquire(6L * 60 * 60 * 1000)
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            try {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AirBridge:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                // 部分机型不支持低延迟锁，退回高性能锁
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirBridge:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseNetworkLocks() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airplay)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("点击停止可结束投射")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun toggleTestTone() {
        if (CastController.senders.isEmpty()) {
            CastController.addLog("当前没有正在投射的会话")
            return
        }
        if (testToneOn) {
            stopTestTone()
            CastController.setTestTone(false)
        } else {
            testToneOn = true
            CastController.setTestTone(true)
            testToneThread = Thread({ testToneLoop() }, "test-tone").also { it.start() }
        }
    }

    private fun stopTestTone() {
        testToneOn = false
        testToneThread?.interrupt()
        testToneThread = null
    }

    private fun testToneLoop() {
        val rate = 44100
        val freq = 440.0
        val buf = ShortArray(2048)
        val sleepMs = (buf.size / 2) * 1000L / rate
        var phase = 0.0
        while (testToneOn) {
            var i = 0
            while (i < buf.size) {
                val v = (Math.sin(phase) * 8000.0).toInt().toShort()
                buf[i] = v
                buf[i + 1] = v
                phase += 2.0 * Math.PI * freq / rate
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                i += 2
            }
            for (s in CastController.senders.values) s.pushPcm(buf, rate)
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
