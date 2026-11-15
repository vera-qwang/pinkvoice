package com.vera.pinkvoice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 频率轴的可见范围，以及调整它的手势逻辑。
 *
 * 纵轴是对数频率轴，所以所有操作都在**对数空间**里做：
 * 平移是整体加一个偏移，缩放是围绕中心拉伸。
 * 直接在线性频率上算会让低频区动得比高频区快得多，手感很怪。
 *
 * 范围本身存在 [mutableFloatStateOf] 里，**在绘制时读取**——
 * 这样滑动时只重画画布，不触发重组（整屏重组是之前 CPU 的大头）。
 */
@Stable
class FrequencyRange internal constructor(
    val defaultMin: Float,
    val defaultMax: Float,
) {
    /** 可见范围下限（Hz）。 */
    var min by mutableFloatStateOf(defaultMin)
        private set

    /** 可见范围上限（Hz）。 */
    var max by mutableFloatStateOf(defaultMax)
        private set

    /** 当前范围是不是默认的。用来决定要不要显示"重置"的提示。 */
    val isDefault: Boolean
        get() = abs(min - defaultMin) < 0.5f && abs(max - defaultMax) < 0.5f

    /** 回到默认范围。 */
    fun reset() {
        min = defaultMin
        max = defaultMax
    }

    /**
     * 上下平移可见范围。
     *
     * @param deltaPx 手指纵向位移（像素，向下为正）
     * @param heightPx 绘图区高度（像素）
     *
     * 方向：**内容跟着手指走**。
     *
     * 手指往上滑 → 曲线往上移 → 露出下面那段 → 看到更低的频率。
     * 和滚动文档、看地图的直觉一致（往上拖地图，看到的是更南边）。
     */
    fun pan(deltaPx: Float, heightPx: Float) {
        if (heightPx <= 0f || deltaPx == 0f) return
        val logMin = ln(min.toDouble()).toFloat()
        val logMax = ln(max.toDouble()).toFloat()
        val span = logMax - logMin
        // 拖过整个绘图区的高度 = 走完一整个跨度的对数距离
        val delta = deltaPx * span / heightPx
        shift(logMin + delta, logMax + delta, span)
    }

    /**
     * 以视野中心为基准缩放。
     *
     * @param zoomChange > 1 放大（看得更细），< 1 缩小（看得更宽）
     */
    fun zoom(zoomChange: Float) {
        if (zoomChange <= 0f) return
        val logMin = ln(min.toDouble()).toFloat()
        val logMax = ln(max.toDouble()).toFloat()
        val center = (logMin + logMax) / 2f
        var span = (logMax - logMin) / zoomChange
        span = span.coerceIn(MIN_LOG_SPAN, MAX_LOG_SPAN)
        shift(center - span / 2f, center + span / 2f, span)
    }

    /** 把 [newMin]..[newMax] 挪回合法范围（不改变跨度）。 */
    private fun shift(newMin: Float, newMax: Float, span: Float) {
        val lo = ln(MIN_HZ.toDouble()).toFloat()
        val hi = ln(MAX_HZ.toDouble()).toFloat()
        var a = newMin
        var b = newMax
        if (a < lo) {
            a = lo
            b = lo + span
        }
        if (b > hi) {
            b = hi
            a = hi - span
        }
        min = exp(a)
        max = exp(b)
    }

    companion object {
        /** 能滑到的最低频率。低于人声范围，留一点余量。 */
        const val MIN_HZ = 40f

        /** 能滑到的最高频率。 */
        const val MAX_HZ = 1000f

        /**
         * 最窄视野的对数跨度。
         *
         * 0.2 ≈ 1.22 倍，够看清相邻的两个半音之间。
         * 再窄音名标签就挤成一坨了。
         */
        const val MIN_LOG_SPAN = 0.2f

        /** 最宽视野：整个 [MIN_HZ]..[MAX_HZ]。 */
        const val MAX_LOG_SPAN = 3.2f
    }
}

/** 记住一个频率轴范围。 */
@Composable
fun rememberFrequencyRange(
    defaultMin: Float = 80f,
    defaultMax: Float = 270f,
): FrequencyRange = remember(defaultMin, defaultMax) { FrequencyRange(defaultMin, defaultMax) }
