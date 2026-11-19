package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 复数。够用就好，不追求性能。 */
data class Complex(val re: Double, val im: Double) {

    operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)

    operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)

    operator fun times(o: Complex) =
        Complex(re * o.re - im * o.im, re * o.im + im * o.re)

    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        if (d == 0.0) return Complex(Double.MAX_VALUE, 0.0)
        return Complex(
            (re * o.re + im * o.im) / d,
            (im * o.re - re * o.im) / d,
        )
    }

    fun abs(): Double = sqrt(re * re + im * im)

    fun arg(): Double = kotlin.math.atan2(im, re)

    companion object {
        fun of(re: Double, im: Double) = Complex(re, im)

        /** 极坐标。 */
        fun polar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta))

        val ZERO = Complex(0.0, 0.0)
    }
}

/**
 * 求多项式的根（Durand-Kerner / Weierstrass 法）。
 *
 * ## 为什么用它
 *
 * LPC 求根是提取共振峰的**标准做法**（Praat 就是这么做的）。
 * 相比从包络上"挑峰"，求根有两个决定性优势：
 *
 * 1. **不受浅峰影响**。周期声源的梳状结构会让包络上出现很多
 *    几乎一样高的起伏（实测 /a/ 的 F2 就是个只有零点几 dB 的小凸起，
 *    按"最高峰"选就会跳过它选到 F3）。
 *    而每个**根**就是一个独立的共振候选，不需要比较高度。
 *
 * 2. **顺序天然明确**。根按频率排好序，F1/F2/F3 的归属是确定的，
 *    不会因为两个峰挨得近就搞混。
 *
 * ## 算法
 *
 * Durand-Kerner 是牛顿法在复平面上的推广：同时迭代所有根，
 * 每个根朝"多项式在这个点为零"的方向修正。
 *
 * ```
 * z_k ← z_k − p(z_k) / Π_{j≠k}(z_k − z_j)
 * ```
 *
 * 初始值取单位圆上均匀分布的点。这个方法对良态多项式很稳。
 */
object PolynomialRoots {

    /**
     * 求根。
     *
     * @param coeffs 系数，**升幂**排列：coeffs[0] + coeffs[1]·y + coeffs[2]·y² + …
     * @return 所有根（复数）
     */
    fun roots(coeffs: DoubleArray, maxIterations: Int = 500): List<Complex> {
        // 去掉末尾的 0 系数（降阶）
        var c = coeffs
        while (c.size > 1 && c[c.size - 1] == 0.0) c = c.copyOf(c.size - 1)
        val n = c.size - 1
        if (n <= 0) return emptyList()

        // 归一化（常数项为 1 的多项式，值的大小差异不影响根，但有助于数值稳定）
        val lead = c[n]
        if (lead != 0.0) c = DoubleArray(c.size) { c[it] / lead }

        // 初始值：均匀分布在一个圆上。
        //
        // 半径按多项式的性质选：LPC 的**反转**多项式的根是极点，
        // 都在单位圆内，所以初值取 0.8 半径更合适（在根附近收敛更快）。
        val z = ArrayList<Complex>(n)
        val radius = 0.8
        for (k in 0 until n) {
            val theta = 2.0 * Math.PI * k / n + 0.4
            z += Complex.polar(radius, theta)
        }

        fun eval(y: Complex): Complex {
            var acc = Complex.ZERO
            for (i in c.indices.reversed()) acc = acc * y + Complex(c[i], 0.0)
            return acc
        }

        for (iter in 0 until maxIterations) {
            var maxDelta = 0.0
            val next = ArrayList<Complex>(n)
            for (k in 0 until n) {
                val zk = z[k]
                var num = eval(zk)
                var den = Complex(1.0, 0.0)
                for (j in 0 until n) {
                    if (j == k) continue
                    den = den * (zk - z[j])
                }
                if (den.abs() < 1e-300) {
                    next += zk
                    continue
                }
                val delta = num / den
                val nk = zk - delta
                next += nk
                if (delta.abs() > maxDelta) maxDelta = delta.abs()
            }
            z.clear()
            z.addAll(next)
            if (maxDelta < 1e-12) break
        }
        return z
    }
}
