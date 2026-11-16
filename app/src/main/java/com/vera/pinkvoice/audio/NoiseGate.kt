package com.vera.pinkvoice.audio

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 返听通路的降噪：**噪声门 + 高通**。
 *
 * 为什么需要：麦克风把整个房间的声音都收进来了。返听是"原样听回自己"，
 * 所以环境底噪、风扇声、远处的说话声会一起进到耳机里——练歌的时候很吵。
 *
 * ## 只作用于返听，不碰分析
 *
 * 分析支路必须保持原样：门限一刀切下去会把**轻声唱**也切掉，
 * 曲线随之断成一段段，音高读数也跟着丢。所以降噪只加在返听这条路上，
 * **曲线和读数永远反映真实的声音**，降噪只影响"耳朵里听到什么"。
 *
 * ## 噪声门
 *
 * 用短时 RMS（10 ms 时间常数）当电平，低于门限就把输出拉到 0。
 * 门限本身随 [level] 从 -70 dB 走到 -25 dB：-70 基本等于不处理，
 * -25 只留很响的部分。
 *
 * 开关门都用斜坡（开 3 ms、关 60 ms），不然门限附近会"哒哒"响。
 *
 * ## 高通
 *
 * 20 Hz 起步、到 80 Hz 为止，砍掉空调、路面那种低频隆隆声。
 * 上限压在 80 Hz 是有意的：男声低音 fundamentals 在 82 Hz 附近，
 * 再往高切就会把基频削掉，练音准时耳朵听到的音色会不对。
 *
 * @param sampleRate 采样率（Hz）
 */
class NoiseGate(private val sampleRate: Int) {

    /**
     * 降噪强度，0..1。
     *
     * 0 = 不处理（完全透明），1 = 最强。
     */
    @Volatile
    var level: Float = 0f
        set(value) {
            val c = value.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                update()
            }
        }

    /** 线性幅度门限。 */
    private var threshold = 0f

    /** 高通的一阶系数。 */
    private var hpAlpha = 1f

    /** 高通是否启用。 */
    private var hpEnabled = false

    /** 包络的能量（平方）。 */
    private var envSq = 0f

    /** 平滑后的门增益。 */
    private var gain = 1f

    /** 高通的历史状态。 */
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    init {
        update()
    }

    /** 强度变化时重算系数。 */
    private fun update() {
        if (level <= 0f) {
            hpEnabled = false
            threshold = 0f
            return
        }
        hpEnabled = true
        // 门限：-70 dB → -25 dB
        val db = MIN_THRESHOLD_DB + (MAX_THRESHOLD_DB - MIN_THRESHOLD_DB) * level
        threshold = Math.pow(10.0, (db / 20.0).toDouble()).toFloat()
        // 高通：20 Hz → 80 Hz
        val fc = MIN_HIGHPASS_HZ + (MAX_HIGHPASS_HZ - MIN_HIGHPASS_HZ) * level
        val rc = 1f / (2f * Math.PI.toFloat() * fc)
        val dt = 1f / sampleRate
        hpAlpha = rc / (rc + dt)
    }

    /** 重新开始（停止监听时调用，避免上一段的残留状态影响下一段）。 */
    fun reset() {
        envSq = 0f
        gain = 1f
        hpPrevIn = 0f
        hpPrevOut = 0f
    }

    /**
     * 处理一段采样。
     *
     * @param input 输入（-1..1）
     * @param count 样本数
     * @param output 输出（-1..1），可以与 input 是同一个数组
     */
    fun process(input: FloatArray, count: Int, output: FloatArray) {
        if (level <= 0f) {
            System.arraycopy(input, 0, output, 0, count)
            return
        }
        val envCoef = 1f - exp(-1f / (ENV_TAU_S * sampleRate))
        val openCoef = 1f - exp(-1f / (OPEN_TAU_S * sampleRate))
        val closeCoef = 1f - exp(-1f / (CLOSE_TAU_S * sampleRate))
        for (i in 0 until count) {
            var x = input[i]
            if (hpEnabled) {
                // 一阶高通：y = a*(y_prev + x - x_prev)
                val y = hpAlpha * (hpPrevOut + x - hpPrevIn)
                hpPrevIn = x
                hpPrevOut = y
                x = y
            }
            // 短时 RMS 包络
            envSq += (x * x - envSq) * envCoef
            val env = sqrt(envSq)
            val target = if (env >= threshold) 1f else 0f
            gain += (target - gain) * (if (target > gain) openCoef else closeCoef)
            output[i] = x * gain
        }
    }

    companion object {
        /** 门限下限（dBFS）：约等于不处理。 */
        const val MIN_THRESHOLD_DB = -70f

        /** 门限上限（dBFS）：很激进。 */
        const val MAX_THRESHOLD_DB = -25f

        /** 高通下限（Hz）：几乎不影响人声。 */
        const val MIN_HIGHPASS_HZ = 20f

        /** 高通上限（Hz）：再高就会削男声基频。 */
        const val MAX_HIGHPASS_HZ = 80f

        /** 包络时间常数（秒）。 */
        private const val ENV_TAU_S = 0.010f

        /** 开门斜坡时间常数（秒）。 */
        private const val OPEN_TAU_S = 0.003f

        /** 关门斜坡时间常数（秒）。 */
        private const val CLOSE_TAU_S = 0.060f
    }
}
