package com.vera.pinkvoice.audio

/**
 * 回放缓冲：持有当前正在播放的快照，并负责从中读数据。
 *
 * 单独抽出来是因为这段逻辑最容易出竞态：
 * - 快照什么时候挂上（必须在返听线程启动**之前**，否则扬声器模式下
 *   线程会以为"没数据可喂"直接退出，回放静默地什么都不响）
 * - 谁在读、读到哪了（只有返听线程读，所以位置不是原子的也没关系）
 *
 * @param maxBytes 单次回放的最大字节数
 */
class ReplayBuffer(private val maxBytes: Int) {

    /**
     * 一段待播放的音频。
     *
     * [pos] 只有返听线程会改，所以不需要同步。
     */
    class Clip(val data: ByteArray) {
        var pos: Int = 0
        val length: Int get() = data.size
    }

    @Volatile
    private var clip: Clip? = null

    /** 当前是否有一段在播。 */
    val active: Boolean get() = clip != null

    /** 剩余未播出的字节数。 */
    val remaining: Int get() = clip?.let { it.length - it.pos } ?: 0

    /**
     * 开始播放 [data]。
     *
     * @return 实际开始播放的字节数；数据为空时返回 0（不开始）。
     */
    fun begin(data: ByteArray): Int {
        if (data.isEmpty() || data.size > maxBytes) return 0
        clip = Clip(data)
        return data.size
    }

    /**
     * 从当前快照里读数据。
     *
     * @return 读出的字节数；**0 表示已经播完**（此时应结束回放）。
     */
    fun read(dst: ByteArray): Int {
        val c = clip ?: return 0
        if (c.pos >= c.length) return 0
        val n = minOf(dst.size, c.length - c.pos)
        System.arraycopy(c.data, c.pos, dst, 0, n)
        c.pos += n
        return n
    }

    /** 结束回放，丢掉快照。 */
    fun finish() {
        clip = null
    }
}
