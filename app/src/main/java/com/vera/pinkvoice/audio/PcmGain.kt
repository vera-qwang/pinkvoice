package com.vera.pinkvoice.audio

/**
 * 给 PCM16 缓冲区加增益（原地修改）。
 *
 * 返听音量要能到 200%，但 Android 的 [android.media.AudioTrack.setVolume]
 * 上限是 1.0——超出的部分只能靠软件增益补。
 *
 * ## 为什么要单独拆出来
 *
 * 这个函数做的是**位运算级别的转换**（小端有符号 16 位），
 * 错了不会崩，只会出来一片噪音——靠耳朵听很难判断对错，
 * 所以要能单元测试。
 */
internal fun applyPcm16Gain(buf: ByteArray, n: Int, gain: Float) {
    var i = 0
    while (i + 1 < n) {
        val u = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
        val s = u.toShort().toInt()
        val v = (s * gain).toInt().coerceIn(-32768, 32767)
        buf[i] = (v and 0xFF).toByte()
        buf[i + 1] = ((v shr 8) and 0xFF).toByte()
        i += 2
    }
}
