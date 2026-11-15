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

    /** 往上滑 → 内容跟着上移 → 看到更低的频率。 */
    @Test
    fun panUpShowsLowerFrequencies() {
        val r = range()
        val before = r.min
        r.pan(deltaPx = -400f, heightPx = 1000f)
        assertTrue("上滑后下限应该降低：${r.min} >= $before", r.min < before)
        assertTrue("上滑后上限也应该降低：${r.max}", r.max < 270f)
    }

    /** 往下滑 → 看到更高的频率。 */
    @Test
    fun panDownShowsHigherFrequencies() {
        val r = range()
        r.pan(deltaPx = 400f, heightPx = 1000f)
        assertTrue("下滑后下限应该升高：${r.min}", r.min > 80f)
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
        assertTrue("整体应该下移了（滑到更低的频率）", r.min < 80f)
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

    private val height = 1000f
    private val labelHeight = 30f
    private val gap = 11f

    /** 分界线在 E3（164.81 Hz）上。 */
    private fun splitY(fMin: Float, fMax: Float) =
        yFor(BandBoundaryHz, height, fMin, fMax)

    /** 视野横跨分界线时，两个标签都该有。 */
    @Test
    fun bothLabelsVisibleWhenRangeSpansBoundary() {
        val split = splitY(80f, 270f)
        assertTrue("女声标签应该在", bandLabelY(true, 80f, 270f, split, height, labelHeight, gap) != null)
        assertTrue("男声标签应该在", bandLabelY(false, 80f, 270f, split, height, labelHeight, gap) != null)
    }

    /** 视野完全在女声区 → 只有女声标签。 */
    @Test
    fun onlyFemaleLabelWhenRangeAboveBoundary() {
        val split = splitY(200f, 400f)   // 都在 164.81 以上
        assertTrue(
            "女声标签应该在",
            bandLabelY(true, 200f, 400f, split, height, labelHeight, gap) != null,
        )
        assertEquals(
            "男声标签不该有",
            null,
            bandLabelY(false, 200f, 400f, split, height, labelHeight, gap),
        )
    }

    /** 视野完全在男声区 → 只有男声标签。 */
    @Test
    fun onlyMaleLabelWhenRangeBelowBoundary() {
        val split = splitY(40f, 135f)
        assertEquals(
            "女声标签不该有",
            null,
            bandLabelY(true, 40f, 135f, split, height, labelHeight, gap),
        )
        assertTrue(
            "男声标签应该在",
            bandLabelY(false, 40f, 135f, split, height, labelHeight, gap) != null,
        )
    }

    /** 标签位置必须始终在画布内——越界就是之前那个崩溃。 */
    @Test
    fun labelAlwaysInsideCanvas() {
        val ranges = listOf(
            40f to 80f,
            60f to 100f,
            80f to 270f,
            164.81f to 255f,
            200f to 500f,
            296f to 1000f,
            82f to 164.81f,
            160f to 170f,
        )
        for ((lo, hi) in ranges) {
            val split = splitY(lo, hi)
            for (female in listOf(true, false)) {
                val y = bandLabelY(female, lo, hi, split, height, labelHeight, gap) ?: continue
                assertTrue(
                    "范围 $lo–$hi 标签 y=$y 超出画布",
                    y >= 0f && y + labelHeight <= height,
                )
            }
        }
    }

    /** 视野横跨分界线时，两个标签不能重叠。 */
    @Test
    fun labelsDoNotOverlap() {
        val split = splitY(80f, 270f)
        val femaleY = bandLabelY(true, 80f, 270f, split, height, labelHeight, gap)!!
        val maleY = bandLabelY(false, 80f, 270f, split, height, labelHeight, gap)!!
        assertTrue("女声标签应该在分界线上方", femaleY + labelHeight <= split + gap)
        assertTrue("男声标签应该在分界线下方", maleY >= split)
    }
}
