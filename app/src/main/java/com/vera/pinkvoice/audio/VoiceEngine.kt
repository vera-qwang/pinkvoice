package com.vera.pinkvoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

sealed interface EngineStatus {
    data object Idle : EngineStatus
    data object Running : EngineStatus
    data class Error(val message: String) : EngineStatus
}

data class LatencyInfo(
    /** 麦克风采样 -> 应用拿到数据的延迟。 */
    val inputMs: Float = 0f,
    /** 应用写入 -> 声卡输出的延迟（不含蓝牙栈）。 */
    val outputMs: Float = 0f,
    /** 输入 + 输出（不含蓝牙 A2DP 缓冲）。 */
    val totalMs: Float = 0f,
    /** 分析链路固有的滞后（FIR + YIN 窗口）。 */
    val analysisMs: Float = 0f,
)

/**
 * 实时音频引擎：麦克风采集 ->（A）直通返听  （B）音高分析。
 *
 * 两条路径完全独立：
 * - 返听走原始 PCM，不做任何滤波，因此监听延迟最低；
 * - 分析支路先 FIR 降采样到约 16 kHz 再做 YIN，避免高次谐波混叠造成倍频误判。
 *
 * 返听写在独立线程上（环形缓冲解耦），保证蓝牙等大缓冲输出设备不会反压
 * 住采集线程——采集一旦阻塞，麦克风缓冲溢出，音高曲线就会出现跳变。
 */
class VoiceEngine(
    private val preferredRate: Int = 48000,
    private val preferredAnalysisRate: Int = 16000,
) {
    companion object {
        private const val TAG = "VoiceEngine"
        private const val IN_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val RING_BYTES = 256 * 1024
        private const val BLOCK_FRAMES = 1024

        /** 延迟 / 丢样的发布间隔（纳秒）。 */
        private const val PUBLISH_INTERVAL_NS = 125_000_000L

        /** 依次尝试的录音采样率：很多机型在 VOICE_RECOGNITION 源下只支持 16 kHz。 */
        private val RATE_CANDIDATES = intArrayOf(48000, 44100, 16000)
    }

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _latency = MutableStateFlow(LatencyInfo())

    /**
     * 视觉对齐量（输出延迟 − 分析延迟），单独发布。
     *
     * 主界面只需要这一个数，而完整延迟明细只在设置面板里看。
     * 分开发布是为了让主界面不必为一个数字订阅整份延迟对象——
     * 否则每次延迟更新都会重组整屏（真机实测这是 CPU 大头）。
     */
    private val _autoOffsetMs = MutableStateFlow(0f)
    val autoOffsetMs: StateFlow<Float> = _autoOffsetMs
    val latency: StateFlow<LatencyInfo> = _latency.asStateFlow()

    private val _droppedBytes = MutableStateFlow(0L)
    val droppedBytes: StateFlow<Long> = _droppedBytes.asStateFlow()

    /** 供 UI 绘制的音高历史（引擎线程写，UI 线程读）。 */
    val history = PitchHistory(capacity = 4096)

    private val ring = PcmRingBuffer(RING_BYTES)
    private val framesWritten = AtomicLong(0)
    private val timestampProbe = TimestampProbe()

    private var activeRate = preferredRate
    private var activeAnalysisRate = preferredAnalysisRate
    private var decimator: FirDecimator? = null
    private var yin: YinPitchDetector? = null
    private var analysisDelayInputSamples = 0

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    private var monitorThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var monitorGain: Float = 0.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player?.setVolume(field)
        }

    /** 当前实际使用的采样率（回退后可能不是首选值）。 */
    val sampleRate: Int get() = activeRate

    val analysisLatencyMs: Float
        get() = analysisDelayInputSamples * 1000f / activeRate

    // ---------------------------------------------------------------- 生命周期

    fun start(): Boolean {
        if (running) return true
        var lastError = "录音器初始化失败"
        for (rate in RATE_CANDIDATES) {
            val err = tryStart(rate)
            if (err == null) return true
            lastError = err
        }
        fail(lastError)
        return false
    }

    /** @return null 表示成功，否则返回错误描述。 */
    private fun tryStart(rate: Int): String? {
        val analysisRate = if (rate >= preferredAnalysisRate) {
            rate / (rate / preferredAnalysisRate)
        } else {
            rate
        }
        val decimation = (rate / analysisRate).coerceAtLeast(1)

        val minRec = AudioRecord.getMinBufferSize(rate, IN_CHANNEL, ENCODING)
        if (minRec <= 0) return "设备不支持 ${rate}Hz 单声道录音"
        val recBuf = maxOf(minRec * 2, BLOCK_FRAMES * 8)

        // 固定用 VOICE_RECOGNITION：该源不做通话链路的后期处理，
        // 分析链路拿到的信号最干净（AEC 已移除）。
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(rate)
                        .setChannelMask(IN_CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(recBuf)
                .build()
        } catch (e: Exception) {
            return "录音器创建失败：${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "${rate}Hz 录音器初始化失败"
        }

        // 分析支路必须关掉 AGC / NS：它们会改写幅度与谐波，直接毁掉 F0 与共振峰
        // 注意：AudioRecord.getAudioId() 是隐藏 API，公开可用的入口是 audioSessionId
        val sessionId = rec.audioSessionId
        runCatching { AutomaticGainControl.create(sessionId)?.setEnabled(false) }
        runCatching { NoiseSuppressor.create(sessionId)?.setEnabled(false) }

        val minTrack = AudioTrack.getMinBufferSize(rate, OUT_CHANNEL, ENCODING)
        if (minTrack <= 0) {
            rec.release()
            return "设备不支持 ${rate}Hz 单声道播放"
        }
        val trackBuf = maxOf(minTrack * 2, BLOCK_FRAMES * 8)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(rate)
                        .setChannelMask(OUT_CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            rec.release()
            return "播放通道创建失败：${e.message}"
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            rec.release()
            return "播放通道初始化失败"
        }

        // ---- 全部就绪，正式开跑 ----
        activeRate = rate
        activeAnalysisRate = analysisRate
        decimator = FirDecimator(
            decimation = decimation,
            cutOffRatio = 6800.0 / rate,
        )
        yin = YinPitchDetector(sampleRate = analysisRate)
        analysisDelayInputSamples = decimator!!.groupDelayInputSamples +
            decimation * yin!!.analysisLagSamples

        recorder = rec
        player = track
        ring.clear()
        decimator!!.reset()
        yin!!.reset()
        history.clear()
        framesWritten.set(0)
        track.setVolume(monitorGain)
        track.play()
        rec.startRecording()

        running = true
        _status.value = EngineStatus.Running
        Log.i(
            TAG,
            "start rate=$activeRate analysis=$activeAnalysisRate " +
                "recBuf=${rec.bufferSizeInFrames} trackBuf=${track.bufferSizeInFrames}",
        )
        captureThread = Thread({ captureLoop(rec, rate, decimator!!, yin!!) }, "voice-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        monitorThread = Thread({ monitorLoop(track) }, "voice-monitor").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return null
    }

    fun stop() {
        if (!running && recorder == null) return
        running = false
        captureThread?.join(500)
        monitorThread?.join(500)
        captureThread = null
        monitorThread = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        recorder = null
        player = null
        _status.value = EngineStatus.Idle
        // 保留最后一次测得的延迟，只把分析耗时刷新（停止后读数归零会让人误以为出错）
        val last = _latency.value
        _latency.value = last.copy(analysisMs = analysisLatencyMs)
        _autoOffsetMs.value = (last.outputMs - analysisLatencyMs).coerceAtLeast(0f)
    }

    private fun fail(message: String) {
        _status.value = EngineStatus.Error(message)
    }

    // ---------------------------------------------------------------- 采集 / 分析

    private fun captureLoop(
        rec: AudioRecord,
        rate: Int,
        decim: FirDecimator,
        detector: YinPitchDetector,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val pcm = ShortArray(BLOCK_FRAMES)
        val bytes = ByteArray(BLOCK_FRAMES * 2)
        val floatsFull = FloatArray(BLOCK_FRAMES)
        val floatsAnalysis = FloatArray(activeAnalysisRate / 8)

        var totalRead = 0L
        var tsFrame = 0L
        var tsNs = 0L
        var sinceAnchor = 0
        var frameCount = 0

        while (running) {
            val n = rec.read(pcm, 0, BLOCK_FRAMES)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) break
                continue
            }
            totalRead += n

            // --- A. 直通返听（原始 PCM，不做任何处理） ---
            for (i in 0 until n) {
                val v = pcm[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            ring.write(bytes, 0, n * 2)

            // --- B. 分析支路 ---
            for (i in 0 until n) floatsFull[i] = pcm[i] / 32768f
            val m = decim.process(floatsFull, n, floatsAnalysis)
            if (m > 0) {
                if (++sinceAnchor >= 8) {
                    sinceAnchor = 0
                    val ts = AudioTimestamp()
                    if (timestampProbe.read(rec, ts)) {
                        tsFrame = ts.framePosition
                        tsNs = ts.nanoTime
                    }
                }
                val analysedFrame = totalRead - analysisDelayInputSamples
                val captureNs = tsNs + (analysedFrame - tsFrame) * 1_000_000_000L / rate
                detector.process(floatsAnalysis, m) { hz, clarity, rms ->
                    history.add(captureNs, hz, clarity, rms)
                    if (++frameCount % 32 == 0) {
                        // 诊断日志：原始检测值 vs 跟踪器输出，便于现场调参
                        val stable = history.sampleAt(captureNs)?.hz ?: 0f
                        Log.d(
                            TAG,
                            "pitch raw=%.1f stable=%.1f clarity=%.2f rms=%.4f".format(
                                hz, stable, clarity, rms,
                            ),
                        )
                    }
                }
            }

            if (n == BLOCK_FRAMES) updateLatency(rec, rate, totalRead)
        }
    }

    /** 用 AudioTimestamp 估算输入/输出延迟。 */
    private fun updateLatency(rec: AudioRecord, rate: Int, totalRead: Long) {
        val recTs = AudioTimestamp()
        val trackTs = AudioTimestamp()
        val track = player ?: return
        if (!timestampProbe.read(rec, recTs)) return
        if (!track.getTimestamp(trackTs)) return

        val now = System.nanoTime()

        // 输入：recTs.nanoTime 时刻采集到的是第 recTs.framePosition 帧
        val capturedNs = recTs.nanoTime +
            (totalRead - recTs.framePosition) * 1_000_000_000L / rate
        val inputMs = (now - capturedNs) / 1_000_000f

        // 输出：刚刚写入的最后一帧会在 playPos + queued 之后被播放
        val written = framesWritten.get()
        val presentedNs = trackTs.nanoTime +
            (written - trackTs.framePosition) * 1_000_000_000L / rate
        // 取整后发布。启动瞬间的估计值可能是负的（AudioTimestamp 的帧位置
        // 偶尔会跑在写入位置前面），负延迟没有物理意义，一律按 0 处理。
        val outputMs = ((presentedNs - now) / 1_000_000f).coerceAtLeast(0f)

        // 测量本身每个音频块（21 ms）做一次，但**发布**要节流。
        //
        // 延迟与丢样是诊断值：主界面只显示一个"对齐 X ms"，设置面板里的
        // 丢样计数更是要按按钮才看得到。原先按 47 Hz 发布，而这两个
        // StateFlow 是在界面顶层被 collect 的，等于让整屏每秒重组 47 次——
        // 真机实测这是 CPU 占用的大头（主线程 80%）。
        if (now - lastPublishNs >= PUBLISH_INTERVAL_NS) {
            lastPublishNs = now
            _autoOffsetMs.value = (outputMs - analysisLatencyMs).coerceAtLeast(0f)
            _latency.value = LatencyInfo(
                inputMs = inputMs.coerceAtLeast(0f),
                outputMs = outputMs,
                totalMs = (inputMs + outputMs).coerceAtLeast(0f),
                analysisMs = analysisLatencyMs,
            )
            _droppedBytes.value = ring.droppedBytes
        }
        if (now - lastLogNs > 2_000_000_000L) {
            lastLogNs = now
            Log.d(
                TAG,
                "latency in=${inputMs.toInt()}ms out=${outputMs.toInt()}ms " +
                    "analysis=${analysisLatencyMs.toInt()}ms dropped=${ring.droppedBytes}",
            )
        }
    }

    private var lastLogNs = 0L

    /** 诊断值发布间隔：8 Hz。 */
    private var lastPublishNs = 0L

    // ---------------------------------------------------------------- 返听

    private fun monitorLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(BLOCK_FRAMES * 4)
        while (running) {
            val n = ring.read(buf, 0, buf.size)
            if (n <= 0) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val w = track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            if (w > 0) framesWritten.addAndGet((w / 2).toLong())
        }
    }
}

/** 读取当前默认音频输出设备类型（蓝牙 / 有线 / 内置扬声器）。 */
object AudioOutputInfo {
    enum class Kind { WIRED, BLUETOOTH, SPEAKER, OTHER }

    data class Device(val kind: Kind, val name: String)

    fun current(audioManager: AudioManager): Device {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferred = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: return Device(Kind.OTHER, "未知设备")

        val kind = when (preferred.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Kind.BLUETOOTH

            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> Kind.WIRED

            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
            else -> Kind.OTHER
        }
        return Device(kind, preferred.productName?.toString() ?: "未知设备")
    }
}
