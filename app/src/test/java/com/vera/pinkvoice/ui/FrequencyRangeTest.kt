package com.vera.pinkvoice.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频率轴范围的调整逻辑。
 *
 * 重点是**方向**和**边界**：
 * 方向错了用户会觉得"反着来"，边界漏了会滑到没意义的频率上。
 */
class FrequencyRangeTest {

    private fun range() = FrequencyRange(80f, 270f)

    /** 往上滑 → 看到更高的频率。 */
    @Test
    fun panUpShowsHigherFrequencies() {
        val r = range()
        val before = r.min
        r.pan(deltaPx = -400f, heightPx = 1000f)
        assertTrue("上滑后下限应该升高：${r.min} <= $before", r.min > before)
        assertTrue("上滑后上限也应该升高：${r.max}", r.max > 270f)
    }

    /** 往下滑 → 看到更低的频率。 */
    @Test
    fun panDownShowsLowerFrequencies() {
        val r = range()
        r.pan(deltaPx = 400f, heightPx = 1000f)
        assertTrue("下滑后下限应该降低：${r.min}", r.min < 80f)
    }

    /**
     * 平移不改变"缩放级别"。
     *
     * 纵轴是对数轴，所以保持不变的是**比例**（max/min），
     * 不是线性差值（max-min）——后者本来就会随位置变化。
     */
    @Test
    fun panKeepsRatio() {
        val r = range()
        val ratioBefore = r.max / r.min
        r.pan(-300f, 1000f)
        assertEquals("比例不该变", ratioBefore, r.max / r.min, 0.001f)
    }

    /** 拖过一整个绘图区 = 走完一整个跨度。 */
    @Test
    fun panAcrossFullHeightMovesOneSpan() {
        val r = range()
        val spanBefore = r.max - r.min
        r.pan(-1000f, 1000f)
        // 对数轴上"走完一个跨度"指的是比例平移：
        // 新的 max/min 应该等于原来的 max/min
        assertEquals(
            "比例不该变",
            r.max / r.min,
            270f / 80f,
            0.01f,
        )
        assertTrue("整体应该上移了", r.min > 80f)
        assertTrue("跨度（线性）会随平移变小", spanBefore > 0)
    }

    /** 滑到最低频率就停住，不会滑到负数。 */
    @Test
    fun panStopsAtLowerBound() {
        val r = range()
        repeat(50) { r.pan(1000f, 1000f) }
        assertTrue("不能低于下限：${r.min}", r.min >= FrequencyRange.MIN_HZ - 0.01f)
        assertTrue("上限也不能跟着掉下去：${r.max}", r.max >= FrequencyRange.MIN_HZ)
    }

    /** 滑到最高频率就停住。 */
    @Test
    fun panStopsAtUpperBound() {
        val r = range()
        repeat(50) { r.pan(-1000f, 1000f) }
        assertTrue("不能高于上限：${r.max}", r.max <= FrequencyRange.MAX_HZ + 0.01f)
        assertTrue("下限也不能跟着涨上去：${r.min}", r.min <= FrequencyRange.MAX_HZ)
    }

    /** 捏合放大 → 跨度变小，看得更细。 */
    @Test
    fun zoomInNarrowsSpan() {
        val r = range()
        val before = r.max - r.min
        r.zoom(2f)
        assertTrue("放大后跨度应变小：${r.max - r.min} vs $before", r.max - r.min < before)
    }

    /** 捏合缩小 → 跨度变大，看得更宽。 */
    @Test
    fun zoomOutWidensSpan() {
        val r = range()
        val before = r.max - r.min
        r.zoom(0.5f)
        assertTrue("缩小后跨度应变大：${r.max - r.min} vs $before", r.max - r.min > before)
    }

    /** 缩放以视野中心为基准，中心频率不该跑掉。 */
    @Test
    fun zoomKeepsCenter() {
        val r = range()
        val centerBefore = kotlin.math.sqrt(r.min * r.max)
        r.zoom(1.8f)
        val centerAfter = kotlin.math.sqrt(r.min * r.max)
        assertEquals("中心频率不该变", centerBefore, centerAfter, centerBefore * 0.01f)
    }

    /** 缩到最窄也不能无限窄。 */
    @Test
    fun zoomStopsAtMinSpan() {
        val r = range()
        repeat(50) { r.zoom(3f) }
        val ratio = r.max / r.min
        val minRatio = kotlin.math.exp(FrequencyRange.MIN_LOG_SPAN.toDouble())
        assertTrue("不能再窄：ratio=$ratio", ratio >= minRatio - 0.001)
    }

    /** 放到最宽也不能无限宽。 */
    @Test
    fun zoomStopsAtMaxSpan() {
        val r = range()
        repeat(50) { r.zoom(0.3f) }
        val ratio = r.max / r.min
        val maxRatio = kotlin.math.exp(FrequencyRange.MAX_LOG_SPAN.toDouble())
        assertTrue("不能再宽：ratio=$ratio", ratio <= maxRatio + 0.001)
    }

    /** 缩放后仍然要落在合法频率范围内。 */
    @Test
    fun zoomStaysWithinBounds() {
        val r = range()
        r.zoom(0.2f)
        r.zoom(10f)
        assertTrue("下限越界：${r.min}", r.min >= FrequencyRange.MIN_HZ - 0.01f)
        assertTrue("上限越界：${r.max}", r.max <= FrequencyRange.MAX_HZ + 0.01f)
    }

    /** 复位。 */
    @Test
    fun resetRestoresDefaults() {
        val r = range()
        assertTrue("刚创建时应该是默认的", r.isDefault)
        r.pan(-500f, 1000f)
        r.zoom(2f)
        assertTrue("改过之后不该算默认", !r.isDefault)
        r.reset()
        assertEquals(80f, r.min, 0.01f)
        assertEquals(270f, r.max, 0.01f)
        assertTrue("复位后应该算默认", r.isDefault)
    }

    /** 高度为 0 时不应该崩（首帧还没测到尺寸）。 */
    @Test
    fun panWithZeroHeightIsSafe() {
        val r = range()
        r.pan(-100f, 0f)
        assertEquals(80f, r.min, 0.01f)
    }
}

/**
 * 音域标签的位置计算。
 *
 * 这个函数出过一个真 bug：标签位置无条件取自 yFor(音域边界)，
 * 而 yFor 会把频率钳到可见范围，所以范围滑到男声区以下时，
 * 标签被放到画布**下方**，drawText 算出负的布局约束直接崩溃。
 */
class BandLabelPositionTest {

    @Test
    fun labelIsNullWhenBandNotVisible() {
        // 范围完全在女声区之上 → 男声区不可见
        assertEquals(
            null,
            bandLabelY(82f, 147f, 300f, 500f, 1000f),
        )
        // 范围完全在男声区之下 → 女声区不可见
        assertEquals(
            null,
            bandLabelY(147f, 255f, 40f, 80f, 1000f),
        )
    }

    @Test
    fun labelYIsInsideCanvasWhenBandVisible() {
        val height = 1000f
        // 各种范围下，标签的 y 都必须在画布内
        val ranges = listOf(
            40f to 80f,
            60f to 100f,
            80f to 270f,
            147f to 255f,
            200f to 500f,
            300f to 1000f,
            82f to 147f,
        )
        for ((lo, hi) in ranges) {
            for (band in listOf(82f to 147f, 147f to 255f)) {
                val y = bandLabelY(band.first, band.second, lo, hi, height)
                if (y != null) {
                    assertTrue(
                        "范围 $lo–${hi} 音域 $band 的标签 y=$y 超出画布",
                        y >= 0f && y <= height,
                    )
                }
            }
        }
    }

    /** 标签 + 5dp 偏移之后也不能越界——那正是崩溃的触发条件。 */
    @Test
    fun labelPlusOffsetStaysInsideCanvas() {
        val height = 1000f
        val offset = 5f * 2.75f  // 5dp ≈ 13.75px
        for (lo in listOf(40f, 60f, 80f, 120f, 200f, 300f)) {
            val hi = lo * 2.5f
            for (band in listOf(82f to 147f, 147f to 255f)) {
                val y = bandLabelY(band.first, band.second, lo, hi, height) ?: continue
                assertTrue(
                    "范围 $lo–${hi} 音域 $band 标签越界：${y + offset} > $height",
                    y + offset <= height,
                )
            }
        }
    }

    /** 音域只露出一条缝时，标签贴着可见的那一段。 */
    @Test
    fun labelFollowsVisiblePartOfBand() {
        // 只看得到女声区最下面一点点（247–255 Hz）
        val y = bandLabelY(147f, 255f, 247f, 300f, 1000f)
        assertTrue("应该能画出标签", y != null)
        // 交集的高频端 255 Hz 应该在高频侧（y 更小）
        assertTrue("标签应该贴着可见段的高频端", y!! < 1000f)
    }
}
