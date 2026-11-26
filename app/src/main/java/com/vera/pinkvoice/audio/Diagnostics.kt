package com.vera.pinkvoice.audio

import android.util.Log

/**
 * 共振峰分析的诊断开关。
 *
 * 打开后每 16 帧（约 256 ms）往 logcat 打一行：
 *
 * ```
 * adb logcat -s PinkVoiceDiag
 * ```
 *
 * 内容是**原始的根列表和 LPC 包络** ——
 * 这样才能判断「界面上的 F1 是真的 F1，还是 F2 顶替的」。
 * 只对着两个数字永远判断不出来。
 *
 * 默认关闭：正常使用不该刷日志。
 */
object Diagnostics {
    @Volatile
    var enabled: Boolean = false

    fun log(msg: String) {
        Log.d("PinkVoiceDiag", msg)
    }
}
