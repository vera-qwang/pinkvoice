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
import kotlin.math.min
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
    /**
     * 是否启用返听（把麦克风声音播回去）。
     *
     * **没有外接耳机时必须关掉**：用手机内置扬声器返听，麦克风会拾取
     * 自己的监听声形成正反馈啸叫，而且分析到的也是被延迟污染的声音。
     * 与其限幅到 35% 还留着这个坑，不如直接不返听——只做音高监测。
     *
     * 引擎侧的表现是完全不创建 AudioTrack、不起返听线程，
     * 连环形缓冲都不写。分析支路完全不受影响。
     */
    @Volatile
    var monitoringEnabled: Boolean = true
    companion object {
        private const val TAG = "VoiceEngine"
        private const val IN_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val RING_BYTES = 256 * 1024
        private const val BLOCK_FRAMES = 1024

        /**
         * 录音缓冲的最大长度（秒）。
         *
         * 48 kHz 单声道 PCM16 = 96 KB/s，30 秒约 2.9 MB。
         * 全程只在堆内存里，不落盘、不上传。
         */
        private const val MAX_RECORD_SECONDS = 30

        /** 回放窗口的默认值（秒）。 */
        const val DEFAULT_REPLAY_SECONDS = 10

        /** 回放窗口的下限（秒）。太短了听不出什么。 */
        const val MIN_REPLAY_SECONDS = 3

        /** 延迟 / 丢样的发布间隔（纳秒）。 */
        private const val PUBLISH_INTERVAL_NS = 125_000_000L

        /** 依次尝试的录音采样率：很多机型在 VOICE_RECOGNITION 源下只支持 16 kHz。 */
        /**
         * 采集速率候选。
         *
         * **只放能整除到 16 kHz 的**。
         *
         * 分析支路固定 16 kHz —— LPC 的参数（阶数、预加重、窗长）
         * 全是在 16 kHz 下标定的，换速率会整体失准。
         *
         * 之前 44100 也在列表里，`rate / (rate / 16000)` 的整数除法
         * 算出 44100/2 = 22050，分析支路变成了 22.05 kHz，
         * 但分析器仍按 16 kHz 换算 —— 所有共振峰乘 16000/22050 = 0.726。
         * 48 kHz 机型碰不到，44.1 kHz 机型（部分设备只支持这个）
         * 会整条链路错掉。
         */
        private val RATE_CANDIDATES = intArrayOf(48000, 16000)
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

    /**
     * 回放窗口长度（秒）：按下回放时播放"最近这么多秒"。
     */
    @Volatile
    var replayWindowSeconds: Int = DEFAULT_REPLAY_SECONDS
        set(value) {
            field = value.coerceIn(MIN_REPLAY_SECONDS, MAX_RECORD_SECONDS)
        }

    private val _replaying = MutableStateFlow(false)

    /** 当前是否正在回放。 */
    val replaying: StateFlow<Boolean> = _replaying.asStateFlow()

    /** 供 UI 绘制的音高历史（引擎线程写，UI 线程读）。 */
    val history = PitchHistory(capacity = 4096)

    private val ring = PcmRingBuffer(RING_BYTES)

    /**
     * 内存录音缓冲——**只活在 RAM 里**，App 一停就消失。
     *
     * 采集线程写，UI 线程在按下"回放"时取快照。
     */
    private val audioRecorder = AudioMemoryRecorder(MAX_RECORD_SECONDS * 48_000 * 2)

    /**
     * 回放缓冲：持有当前正在播放的快照。
     *
     * 状态机（挂快照 → 播 → 结束）全在 [ReplayBuffer] 里，
     * 这样竞态最容易出错的那段可以用单元测试覆盖。
     */
    private val replay = ReplayBuffer(MAX_RECORD_SECONDS * 48_000 * 2)

    /** 保护回放状态机的切换。 */
    private val replayLock = Any()

    /** 返听线程的"有新活干"信号。 */
    private val monitorSignal = MonitorSignal()

    private val framesWritten = AtomicLong(0)
    private val timestampProbe = TimestampProbe()

    private var activeRate = preferredRate
    private var activeAnalysisRate = preferredAnalysisRate
    private var decimator: FirDecimator? = null
    private var yin: YinPitchDetector? = null
    private var analysisDelayInputSamples = 0

    private var recorder: AudioRecord? = null

    @Volatile
    private var player: AudioTrack? = null

    /**
     * 播放通道当前是不是在做**实时返听**。
     *
     * false = 这个 AudioTrack 只是为"回放"临时建的（扬声器模式：
     * 没有耳机，不做实时返听避免啸叫，但回放还是要能出声）。
     *
     * 区分这个是为了回放结束后的收尾：
     * - true  → 回到实时返听，线程继续跑
     * - false → 没有实时返听可恢复，线程退出
     */
    @Volatile
    private var liveMonitorOn = false

    /**
     * 嗓音质量分析器（元音 / 稳态度 / 音色轴）。
     *
     * **默认不跑** —— 监测页只需要 f0 曲线，LPC 是额外的 CPU 开销。
     * 训练页打开时才启用。
     */
    private var quality: VoiceQualityAnalyzer? = null

    /** 质量分析当前是否启用。 */
    @Volatile
    var qualityAnalysisEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                quality = VoiceQualityAnalyzer(sampleRate = preferredAnalysisRate)
            } else {
                quality = null
                _qualityState.value = QualityState.EMPTY
            }
        }

    /** 开始录基准（音色轴的个人基准 + 声道长度校准）。 */
    /** 质量分析的结果（给 UI 订阅）。 */
    private val _qualityState = MutableStateFlow(QualityState.EMPTY)
    val qualityState: StateFlow<QualityState> = _qualityState.asStateFlow()

    /** 返听降噪器（噪声门 + 高通）。 */
    private var noiseGate: NoiseGate? = null
    private var captureThread: Thread? = null
    private var monitorThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * 返听音量，0..2（0%–200%）。
     *
     * Android 的 [AudioTrack.setVolume] 上限是 1.0，所以超过 100% 的部分
     * 没法靠硬件音量——得在**监听通路**上做软件增益。
     *
     * 注意软件增益只加在**送到扬声器/耳机**的那一路：
     * 录音（供回放用）保持原样，否则回放出来的是被放大过的、
     * 可能已经削波失真的声音。
     */
    @Volatile
    var monitorGain: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 2f)
            // 硬件音量只能到 1.0，多出来的交给软件增益
            player?.setVolume(min(field, 1f))
        }

    /**
     * 返听降噪强度，0..1。
     *
     * **只作用于返听通路**——分析支路保持原样：门限一刀切下去会把
     * 轻声唱也切掉，曲线会断成一段段。曲线和读数永远反映真实的声音。
     */
    @Volatile
    var noiseReductionLevel: Float = 0.0f
        set(value) {
            val c = value.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                noiseGate?.level = c
            }
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
        // 分析支路固定 16 kHz。不整除的速率直接跳过 ——
        // 硬算的话会得到 22050 这种非预期速率，
        // 而分析器仍按 16 kHz 换算，结果整体偏。
        if (rate < preferredAnalysisRate || rate % preferredAnalysisRate != 0) {
            return "设备不支持 ${rate}Hz（无法整除到 ${preferredAnalysisRate}Hz）"
        }
        val analysisRate = preferredAnalysisRate
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

        // 返听通道：只在允许返听时才创建。
        // 没有外接设备时完全不创建 AudioTrack——省内存、省 CPU，
        // 也从根本上杜绝了"内置扬声器拾取自己"的正反馈。
        noiseGate = NoiseGate(rate).also { it.level = noiseReductionLevel }
        val track = if (monitoringEnabled) createTrack(rate) else null
        if (monitoringEnabled && track == null) {
            rec.release()
            return "播放通道创建失败"
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
        liveMonitorOn = track != null
        ring.clear()
        noiseGate?.reset()
        decimator!!.reset()
        yin!!.reset()
        history.clear()
        framesWritten.set(0)
        track?.setVolume(min(monitorGain, 1f))
        track?.play()
        rec.startRecording()

        running = true
        _status.value = EngineStatus.Running
        Log.i(
            TAG,
            "start rate=$activeRate analysis=$activeAnalysisRate " +
                "recBuf=${rec.bufferSizeInFrames} " +
                "trackBuf=${track?.bufferSizeInFrames ?: 0} " +
                "monitor=${if (track != null) "on" else "off"}",
        )
        captureThread = Thread({ captureLoop(rec, rate, decimator!!, yin!!) }, "voice-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        if (track != null) {
            monitorThread = Thread({ monitorLoop(track) }, "voice-monitor").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
        return null
    }

    /**
     * 回放最近 [replayWindowSeconds] 秒。
     *
     * 走的是**同一个 AudioTrack**——回放期间把喂给它的数据源从
     * "实时环形缓冲"换成"回放快照"，实时返听自然就停了。
     *
     * 另开一个 AudioTrack 的话，蓝牙耳机下会出现两个流抢输出设备，
     * 切换延迟、音量打架都是麻烦。
     *
     * @return 是否真的开始回放（没在监听 / 没录音 / 没耳机时返回 false）
     */
    fun startReplay(): Boolean {
        if (!running) return false

        // 先取快照。取不到就什么都别做——
        // 建了通道再发现没东西可放，白白占一个 AudioTrack。
        val bytesPerSecond = activeRate * 2
        val want = (replayWindowSeconds * bytesPerSecond)
            .coerceAtMost(audioRecorder.availableBytes())
        if (want <= 0) return false
        val data = ByteArray(want)
        val got = audioRecorder.snapshotRecent(data, 0, want)
        if (got <= 0) return false

        // 通道：耳机模式下它已经在做实时返听（直接复用），
        // 扬声器模式下它是新建的、只为回放而存在。
        val track = ensureTrack() ?: return false

        // **先把快照挂上，再拉线程。**
        //
        // 顺序反了会出事：扬声器模式下返听线程看到"没有快照且没有实时返听"
        // 就会认为没数据可喂、直接退出——如果线程比快照先就位，
        // 回放会静默地什么都不响。
        //
        // "检查是否已在回放"和"挂上快照"必须原子：
        // 连点两下的话，两个调用都通过了检查，后一个把前一个的快照盖掉，
        // 播放位置归零——听感上是"刚播的又从头来了一遍"。
        synchronized(replayLock) {
            if (replay.active) return false
            replay.begin(data)
            _replaying.value = true
        }
        syncMonitorThread()
        // 用 Long 算：got 最多 288 万字节，×1000 就超过 Int 上限了
        // （30 秒窗口时 got*1000 = 28.8 亿 > 21.47 亿，会溢出成负数）。
        val ms = got.toLong() * 1000L / (activeRate.toLong() * 2L)
        Log.i(TAG, "REPLAY start ms=$ms avail=${audioRecorder.availableBytes()}")
        return true
    }

    /**
     * 开始 / 停止回放，由引擎自己判断当前状态。
     *
     * UI 按钮**不能**根据自己看到的 [replaying] 来决定做什么——
     * 那个状态是从 StateFlow 来的，可能比引擎的实际状态滞后一帧。
     * 滞后的时候用户点"停止"，代码却按"还没在回放"去 start，
     * 结果点了没反应。
     *
     * 这里在引擎内部原子地判断，UI 只管发指令。
     *
     * @return 这次操作的结果。UI 靠它决定要不要提示。
     */
    fun toggleReplay(): ReplayAction {
        synchronized(replayLock) {
            if (replay.active) {
                replay.finish()
                _replaying.value = false
                ring.clear()
                syncMonitorThread()
                Log.i(TAG, "REPLAY stop")
                return ReplayAction.STOPPED
            }
        }
        return if (startReplay()) ReplayAction.STARTED else ReplayAction.FAILED
    }

    /**
     * 停止回放，回到实时返听。
     *
     * 实时环形缓冲会被清空——不然回放结束后会接着播 2.7 秒前的旧声音，
     * 听起来像"延迟了一下才恢复"。
     */
    fun stopReplay() {
        finishReplay()
        // 线程和通道的状态也得跟上：
        // - 扬声器模式 → 没有实时返听可回，线程退出、通道暂停
        // - 耳机模式   → 回到实时返听，线程继续跑
        syncMonitorThread()
        Log.i(TAG, "REPLAY stop")
    }

    /**
     * 回放结束的收尾：清掉快照、恢复状态。
     *
     * 实时环形缓冲也清掉——不然回放结束后会接着播 2.7 秒前的旧声音，
     * 听起来像"延迟了一下才恢复"。
     */
    private fun finishReplay() {
        synchronized(replayLock) {
            replay.finish()
            _replaying.value = false
            ring.clear()
        }
    }

    /**
     * 运行时切换返听。
     *
     * 蓝牙耳机被拔掉时必须**立刻**关掉返听——否则声音会从手机扬声器播出来，
     * 麦克风再拾取自己的声音形成啸叫。这个开关不能只在启动时生效，
     * 得能在运行中改。
     */
    fun applyMonitoringEnabled(enabled: Boolean) {
        if (monitoringEnabled == enabled) return
        monitoringEnabled = enabled
        if (!running) return

        if (enabled) {
            // 复用已有的通道——扬声器模式下回放建的通道在这儿接上，
            // 不能再建一个：两个 AudioTrack 会抢输出设备，而且原来的会泄漏。
            val track = ensureTrack()
            if (track == null) {
                monitoringEnabled = false
                Log.w(TAG, "运行时开启返听失败，保持关闭")
                return
            }
            track.setVolume(min(monitorGain, 1f))
            liveMonitorOn = true
            syncMonitorThread()
            Log.i(TAG, "monitoring enabled at runtime")
        } else {
            liveMonitorOn = false
            if (replay.active) {
                // 回放还在进行，通道得留着（线程继续把快照播完）
                syncMonitorThread()
            } else {
                stopMonitorThread()
                runCatching { player?.stop() }
                runCatching { player?.release() }
                player = null
            }
            Log.i(TAG, "monitoring disabled at runtime")
        }
    }

    /**
     * 拿到播放通道，没有就建一个。
     *
     * **始终只有一个 AudioTrack**——耳机模式下它做实时返听，
     * 扬声器模式下它做回放，同一个通道在不同时间干不同的事。
     */
    private fun ensureTrack(): AudioTrack? {
        player?.let { return it }
        val track = createTrack(activeRate) ?: return null
        player = track
        return track
    }

    /**
     * 让返听线程和播放通道的状态对上。
     *
     * 线程存在的条件：通道在，而且**要么**在实时返听**要么**在回放。
     * 两者都没有 → 线程退出、通道暂停（但不释放，
     * 下次回放还能复用，也避免和"建新通道"抢同一个对象）。
     */
    private fun syncMonitorThread() {
        val track = player
        val need = track != null && (liveMonitorOn || replay.active)
        val alive = monitorThread?.isAlive == true
        if (need && !alive) {
            track!!.setVolume(min(monitorGain, 1f))
            track.play()
            startMonitorThread(track)
        } else if (need) {
            // 线程活着但可能在等活——叫醒它。
            signalMonitor()
        } else if (alive) {
            stopMonitorThread()
            runCatching { track?.pause() }
        }
    }

    /**
     * 叫醒可能正在等待的返听线程。
     *
     * 代号必须在**锁里面**加——等待方也是在锁里面比对代号，
     * 这样"检查条件"和"进入等待"之间不会插进别人。
     */
    private fun signalMonitor() {
        monitorSignal.signal()
    }

    /** 创建返听播放通道。失败返回 null。 */
    private fun createTrack(rate: Int): AudioTrack? {
        val minTrack = AudioTrack.getMinBufferSize(rate, OUT_CHANNEL, ENCODING)
        if (minTrack <= 0) return null
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
            Log.w(TAG, "播放通道创建失败：${e.message}")
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        return track
    }

    private fun startMonitorThread(track: AudioTrack) {
        monitorThread = Thread({ monitorLoop(track) }, "voice-monitor").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopMonitorThread() {
        // 先叫醒它：线程空闲时在 monitorSignal 上等（最长 200 ms），
        // 不叫醒的话 join 要白等那么久——这是在 UI 线程上等的。
        monitorSignal.signal()
        monitorThread?.join(500)
        monitorThread = null
    }

    fun stop() {
        if (!running && recorder == null) return
        running = false
        replay.finish()
        _replaying.value = false
        // 叫醒可能在等的返听线程，别让 stop() 白等 200 ms
        monitorSignal.signal()
        captureThread?.join(500)
        stopMonitorThread()
        captureThread = null
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
        val floatsMonitor = FloatArray(BLOCK_FRAMES)
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

            // 浮点化——返听和分析共用这一份，省一次转换
            for (i in 0 until n) floatsFull[i] = pcm[i] / 32768f

            // --- A. 降噪 + 转字节 ---
            // 返听和录音共用这一份：录的是**降噪之后**的信号，
            // 回放时听到的应该和当时实时返听里听到的一致，
            // 否则"回放"没法拿来对比。
            val gate = noiseGate
            if (gate != null && noiseReductionLevel > 0f) {
                gate.process(floatsFull, n, floatsMonitor)
            } else {
                System.arraycopy(floatsFull, 0, floatsMonitor, 0, n)
            }
            for (i in 0 until n) {
                val v = (floatsMonitor[i] * 32768f).toInt().coerceIn(-32768, 32767)
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }

            // --- B. 录音（内存，供"回放"用） ---
            //
            // 回放期间**不录**：扬声器模式下播放的声音会被麦克风收进去，
            // 录下来就是自己的回声。而且回放时用户多半在听，不唱。
            if (!replay.active) {
                audioRecorder.write(bytes, 0, n * 2)
            }

            // --- C. 实时返听 ---
            // 只有耳机在当输出设备时才做（扬声器会啸叫）。
            //
            // 回放期间**不写**：这时候喂给通道的是回放快照，
            // 环形缓冲没人读。继续写只会把它写满——既白费力气，
            // 又把"丢样"计数刷成一个假的巨大数字（真机实测：
            // 一次 10 秒回放能让计数涨到 60 万字节）。
            if (liveMonitorOn && !replay.active) {
                ring.write(bytes, 0, n * 2)
            }

            // --- B. 分析支路 ---
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
                // 质量分析：和 YIN 共用同一份 16 kHz 数据，
                // 但各自维护自己的滑动窗口（窗口/跳帧相同）。
                // 诊断模式（debug 版）下强制启用质量分析 ——
            // 否则只有在 F1/F2 页才跑 LPC，抓不到诊断数据。
            if (Diagnostics.enabled && quality == null) {
                quality = VoiceQualityAnalyzer(sampleRate = activeAnalysisRate)
            }

                detector.process(floatsAnalysis, m) { hz, clarity, rms ->
                    // 用 YIN 的判定给共振峰分析做门控：
                    // 不是语音帧就根本不跑 LPC，否则环境噪音会被
                    // 拟合成「看起来很合理」的共振峰，界面在没人说话时乱跳。
                    //
                    // 阈值 0.5：实测语音段 clarity 中位 0.73–0.85（p10 ≥ 0.58），
                    // 环境噪音段是 0.00。中间余量很大。
                    quality?.voiced = hz > 0f && clarity >= 0.5f
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

            // 放在 detector 之后：门控用的是**本块**的判定，不是上一块的。
            quality?.process(floatsAnalysis, m)

            if (n == BLOCK_FRAMES) updateLatency(rec, rate, totalRead)
        }
    }

    /** 用 AudioTimestamp 估算输入/输出延迟。 */
    private fun updateLatency(rec: AudioRecord, rate: Int, totalRead: Long) {
        val recTs = AudioTimestamp()
        val trackTs = AudioTimestamp()
        val track = player
        if (!timestampProbe.read(rec, recTs)) return
        // 没有返听通道时（A2DP 之外的场景、或未连耳机）测不了输出延迟，
        // 但采集与分析延迟照样要测——设置面板里要能看到。
        if (track != null && !track.getTimestamp(trackTs)) return

        val now = System.nanoTime()

        // 输入：recTs.nanoTime 时刻采集到的是第 recTs.framePosition 帧
        val capturedNs = recTs.nanoTime +
            (totalRead - recTs.framePosition) * 1_000_000_000L / rate
        val inputMs = (now - capturedNs) / 1_000_000f

        // 输出：刚刚写入的最后一帧会在 playPos + queued 之后被播放
        val outputMs = if (track == null) {
            // 不返听 → 没有输出延迟
            0f
        } else {
            val written = framesWritten.get()
            val presentedNs = trackTs.nanoTime +
                (written - trackTs.framePosition) * 1_000_000_000L / rate
            // 取整后发布。启动瞬间的估计值可能是负的（AudioTimestamp 的帧位置
            // 偶尔会跑在写入位置前面），负延迟没有物理意义，一律按 0 处理。
            ((presentedNs - now) / 1_000_000f).coerceAtLeast(0f)
        }

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
            publishQuality()
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

    /** 把质量分析的结果发布出去（节流）。 */
    private fun publishQuality() {
        val q = quality ?: return
        _qualityState.value = QualityState(
            formants = q.formants,
            recognized = q.recognized,
            recognizedDistance = q.recognizedDistance,
            valid = q.validFrames > 4,
            holdSeconds = q.holdSeconds,
        )
    }

    /**
     * 设置训练目标元音。
     *
     * 目标决定了两件事：
     * - 界面上"离目标多远"的对比
     * - "连续保持在目标区里多久了"的计时
     */
    fun setVowelTarget(v: VowelTarget?) {
        quality?.selectTarget(v)
    }

    private var lastLogNs = 0L

    /** 诊断值发布间隔：8 Hz。 */
    private var lastPublishNs = 0L

    // ---------------------------------------------------------------- 返听

    private fun monitorLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(BLOCK_FRAMES * 4)
        while (running) {
            // 没有实时返听、也没有快照 → 暂时没事干。
            //
            // 这里**不能直接退出线程**：退出和"UI 线程要重建线程"之间有竞态——
            // UI 线程看到 isAlive 还是 true 就不重建，然后本线程退出了，
            // 结果一个线程都没有，回放静默地什么都不响。
            //
            // 改成"等着"，由 UI 线程用 [signalMonitor] 叫醒。
            if (!replay.active && !liveMonitorOn) {
                // 等着。醒来后重新检查条件（signal 那边可能派了新活）。
                if (!monitorSignal.awaitWork(false)) return
                continue
            }
            val n = if (replay.active) {
                // 回放中：喂快照，不再喂实时环形缓冲——实时返听自然就停了。
                val got = replay.read(buf)
                if (got == 0) {
                    // 快照播完 → 回到实时返听（扬声器模式下会在循环开头退出线程）
                    finishReplay()
                    Log.i(TAG, "REPLAY end")
                    0
                } else {
                    got
                }
            } else {
                ring.read(buf, 0, buf.size)
            }
            if (n <= 0) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            // 通道可能已经被释放了（stop() 和本线程有竞争）：
            // WRITE_BLOCKING 卡在满缓冲上时，stop() 的 join 会超时，
            // 然后通道被 release，这时 write 会抛 IllegalStateException。
            // 捕获它、干净地退出线程，别让异常冒到线程外面。
            // 超过 100% 的部分用软件增益补（硬件音量到 1.0 就封顶了）。
            //
            // 放在这里而不是采集线程：这里写出去的才是"要出声的东西"，
            // 采集线程那条录音通路保持原样，回放才不会被放大失真。
            val g = monitorGain
            if (g > 1.001f) applyPcm16Gain(buf, n, g)

            val w = try {
                track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "播放通道已释放，返听线程退出：${e.message}")
                return
            }
            if (w > 0) framesWritten.addAndGet((w / 2).toLong())
        }
    }
}

/** 回放操作的结果。 */
enum class ReplayAction {
    /** 开始回放成功。 */
    STARTED,

    /** 停止回放成功。 */
    STOPPED,

    /** 没开始成（还没录到声音之类）。 */
    FAILED,
}

/** 读取当前默认音频输出设备类型（蓝牙 / 有线 / 内置扬声器）。 */
object AudioOutputInfo {
    enum class Kind { WIRED, BLUETOOTH, SPEAKER, OTHER }

    data class Device(val kind: Kind, val name: String)

    fun current(audioManager: AudioManager): Device {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // 只认**当前连着**的输出设备。
        //
        // getDevices(GET_DEVICES_OUTPUTS) 返回的是系统当前的输出设备集合：
        // 蓝牙耳机连上且接管音频时会出现，拔掉后就没了（已真机验证）。
        // 配对但没连上的耳机不会出现在这个列表里。
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
