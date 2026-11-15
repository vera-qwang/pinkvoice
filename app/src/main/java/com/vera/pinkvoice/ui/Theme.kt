package com.vera.pinkvoice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * MTF（Male to Female，跨性别声音训练）品牌色。
 *
 * 取自跨性别旗：浅蓝 #5CCEFA + 粉 #F5A8B8 + 白。
 *
 * 分两档用：
 * · **填充色**（本组）适合大面积色块——按钮、芯片、色带背景。
 *   浅色主题下它们做背景，配深墨蓝文字，对比度 7.4–7.7:1，达 WCAG AA。
 * · **描边色**（[BrandBlueInk] / [BrandPinkInk]）用于浅色背景上的细线和小字。
 *   品牌浅色本身在白底上只有 1.8:1，做 2.6dp 的曲线描边几乎看不见，
 *   所以另备一组更深的同色系（白底 3.6–3.8:1，达 WCAG 对图形元素的 3:1 要求）。
 */
val BrandBlue = Color(0xFF5CCEFA)
val BrandBlueSoft = Color(0xFF8BDCFB)
val BrandPink = Color(0xFFF5A8B8)
val BrandPinkSoft = Color(0xFFFFC2D3)

/** 品牌蓝的描边档（浅色背景上的细线/小字，白底对比度 3.61:1）。 */
val BrandBlueInk = Color(0xFF2A8FC4)

/** 品牌粉的描边档（浅色背景上的细线/小字，白底对比度 3.76:1）。 */
val BrandPinkInk = Color(0xFFDB5480)

/** 品牌浅色上的文字色（深墨蓝，对比度 7.4–7.7:1）。 */
val InkOnBrand = Color(0xFF063043)

/**
 * 主题模式。
 *
 * @param storageKey 持久化用的键（[com.vera.pinkvoice.Prefs]）。
 */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: System
    }
}

/**
 * 男声 / 女声参考音域。
 *
 * **两个区间不重叠**：原先男声 85–180、女声 165–255 重叠了 165–180，
 * 带来两个问题——
 *
 * 1. 重叠区画色带时男声压在女声上面，蓝粉两层混在一起，颜色不干净；
 * 2. 音名着色按"先判男声区"的规则走，于是 165–180 的音名（E3/F3）
 *    显示成蓝色，可它们明明画在粉色带上，看着别扭。
 *
 * **分界点定在 147 Hz（D3）**，而不是随手取个整数。两个原因：
 *
 * - **视觉平衡**：频率轴是对数轴，色带的"大小"看的是比值不是差值。
 *   分界在 165 时男声区跨 85→165（1.94 倍），女声区跨 165→255（1.55 倍），
 *   男声区在对数轴上占 60%、女声区只占 40%——蓝色块明显更大。
 *   分界挪到 √(85×255) ≈ 147 Hz（几何中点）后，两区的对数跨度
 *   基本相等（48% vs 45%），视觉上才是"两个差不多大的色带"。
 * - **落在整音上**：147 Hz 正好是 D3，音名标注不会出现
 *   "D3 蓝、E3 粉"这种看起来像错位的跳变。
 */
val MaleRange = 82f..147f
val FemaleRange = 147f..255f

/** 两个音域的分界点（Hz）。上界归女声，下界归男声。 */
val BandBoundaryHz = 147f

/**
 * 声学相关的语义色。深浅色主题下取值不同，所以单独抽出来，
 * 避免在 Canvas 里到处 if (isSystemInDarkTheme())。
 */
data class VoicePalette(
    val maleBand: Color,
    val femaleBand: Color,
    val trace: Color,
    val grid: Color,
    val axisLabel: Color,
    val inTune: Color,
    val nearTune: Color,
    val offTune: Color,
    val meterTrack: Color,
    val meterFill: Color,
    val needleInner: Color,
    val cardBorder: Color,
    /** 当前音高指示（虚线 + 圆点）的颜色，用描边档保证细线也看得清。 */
    val primaryInk: Color,
    /** 页面背景的渐变上色（浅色偏蓝、下色偏粉，呼应跨性别旗）。 */
    val backgroundTop: Color,
    val backgroundBottom: Color,
)

// 浅色主题下的语义色都按 WCAG 校过：
//   axisLabel 4.75:1（原 2.56:1，10sp 的刻度文字太淡）
//   inTune 3.42:1、nearTune 5.14:1、offTune 3.91:1（图形元素要求 ≥3:1）
//   trace / meterFill 用描边档品牌色（3.76:1 / 3.61:1）
private val LightPalette = VoicePalette(
    maleBand = BrandBlue.copy(alpha = 0.24f),
    femaleBand = BrandPink.copy(alpha = 0.30f),
    trace = BrandPinkInk,
    grid = Color(0x140F1115),
    axisLabel = Color(0x9E11161C),
    inTune = Color(0xFF0E9F5E),
    nearTune = Color(0xFFC2410C),
    offTune = Color(0xFFE5484D),
    meterTrack = Color(0xFFE9ECF3),
    meterFill = BrandBlueInk,
    needleInner = Color.White,
    cardBorder = Color(0x0F0F1115),
    primaryInk = BrandBlueInk,
    backgroundTop = Color(0xFFF4F8FE),
    backgroundBottom = Color(0xFFFDF6F9),
)

// 深色主题下品牌浅色在深底上对比度有 11:1 以上，直接用浅色档即可。
private val DarkPalette = VoicePalette(
    maleBand = BrandBlue.copy(alpha = 0.18f),
    femaleBand = BrandPink.copy(alpha = 0.20f),
    trace = BrandPinkSoft,
    grid = Color(0x1AFFFFFF),
    axisLabel = Color(0x8CFFFFFF),
    inTune = Color(0xFF32D583),
    nearTune = Color(0xFFFDB022),
    offTune = Color(0xFFF97066),
    meterTrack = Color(0xFF2A3040),
    meterFill = BrandBlueSoft,
    needleInner = Color(0xFF0E1014),
    cardBorder = Color(0x1AFFFFFF),
    primaryInk = BrandBlueSoft,
    backgroundTop = Color(0xFF0D1118),
    backgroundBottom = Color(0xFF16101A),
)

val LocalVoicePalette = staticCompositionLocalOf { LightPalette }

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = InkOnBrand,
    secondary = BrandPink,
    onSecondary = InkOnBrand,
    background = Color(0xFFF4F8FE),
    onBackground = Color(0xFF0F1115),
    surface = Color.White,
    onSurface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFFEDEFF6),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFD9DDE7),
    outlineVariant = Color(0xFFE9ECF3),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueSoft,
    onPrimary = InkOnBrand,
    secondary = BrandPinkSoft,
    onSecondary = Color(0xFF4A1226),
    background = Color(0xFF0D1118),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF222732),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF2C313C),
    outlineVariant = Color(0xFF232833),
    error = Color(0xFFF97066),
    onError = Color(0xFF2A0A0A),
)

@Composable
fun PinkVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVoicePalette provides if (darkTheme) DarkPalette else LightPalette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
