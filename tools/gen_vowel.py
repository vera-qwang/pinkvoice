#!/usr/bin/env python3
"""生成合成元音的 wav（给真机验证用）。

用法:
    python3 gen_vowel.py a 4 > /tmp/vowel_a.wav      # 生成
    python3 gen_vowel.py a 4 --check                  # 只看频谱

## 为什么要有这个工具

真机验证需要一个**已知共振峰**的信号，才能判断 App 测得准不准。

## 一个容易踩的坑：合成的频谱比例

最朴素的实现是「声门源 → 级联三个共振器」。但这样出来的
F2 会比 F1 低 **26 dB** —— 真实的元音不是这样的。

真实元音的三个共振峰高度是**接近的**（F1 最强，F2 低 5–15 dB，
F3 再低一点）。原因是真实声道的共振峰峰值增益取决于该处的
反射和损耗，不是级联里"前一级把后一级喂弱了"那么简单。

用不真实的信号做验证，结论会跑偏：
F2 只比 F1 低 26 dB 时，经过笔记本喇叭和房间之后它直接掉进噪声底，
App 自然测不到 —— 但那是**信号的问题**，不是 App 的问题。

所以这里在级联之后做一次**峰值均衡**，把三个共振峰拉到真实比例。
"""
import math
import struct
import sys

SR = 16000

# 各元音：f0 + 三个共振峰（归一化到 17.5 cm 标准声道）
VOWELS = {
    "a": (120.0, [730.0, 1090.0, 2440.0]),
    "i": (200.0, [270.0, 2290.0, 3010.0]),
    "u": (120.0, [300.0, 870.0, 2240.0]),
    "ae": (150.0, [660.0, 1720.0, 2410.0]),
    "eh": (140.0, [530.0, 1840.0, 2480.0]),
    "ao": (130.0, [570.0, 840.0, 2410.0]),
}

# 共振峰的相对电平（dB，相对 F1）—— 接近真实人声
LEVELS_DB = [0.0, -6.0, -10.0]


def glottal_flow(f0, n):
    """Rosenberg 声门流脉冲。

    频谱约 -12 dB/oct（不是脉冲串那种梳状谱），
    零点和共振峰不打架。
    """
    out = []
    period = SR / f0
    for i in range(n):
        p = (i % period) / period
        if p < 0.4:
            t = p / 0.4
            out.append(3 * t * t - 2 * t * t * t)
        elif p < 0.56:
            t = (p - 0.4) / 0.16
            out.append(1.0 - (1.0 - t) ** 2)
        else:
            out.append(0.0)
    return out


def resonator(x, freq, bw):
    """二阶共振器（级联用）。"""
    r = math.exp(-math.pi * bw / SR)
    th = 2.0 * math.pi * freq / SR
    c = 2.0 * r * math.cos(th)
    r2 = r * r
    y = []
    for n in range(len(x)):
        y1 = y[n - 1] if n >= 1 else 0.0
        y2 = y[n - 2] if n >= 2 else 0.0
        y.append(x[n] + c * y1 - r2 * y2)
    return y


def peaking_eq(x, freq, gain_db, q):
    """峰值均衡滤波器（RBJ cookbook）。

    用来把某个共振峰抬到/压到想要的电平。
    """
    if abs(gain_db) < 0.01:
        return x
    a = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * math.pi * freq / SR
    alpha = math.sin(w0) / (2.0 * q)
    b0, b1, b2 = 1 + alpha * a, -2 * math.cos(w0), 1 - alpha * a
    a0, a1, a2 = 1 + alpha / a, -2 * math.cos(w0), 1 - alpha / a
    b0, b1, b2 = b0 / a0, b1 / a0, b2 / a0
    a1, a2 = a1 / a0, a2 / a0
    y = []
    for n in range(len(x)):
        xn = x[n]
        x1 = x[n - 1] if n >= 1 else 0.0
        x2 = x[n - 2] if n >= 2 else 0.0
        y1 = y[n - 1] if n >= 1 else 0.0
        y2 = y[n - 2] if n >= 2 else 0.0
        y.append(b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2)
    return y


def goertzel(seg, f):
    """单频点的幅度（Goertzel 算法，比整段 FFT 轻）。"""
    wq = 2 * math.pi * f / SR
    c = 2 * math.cos(wq)
    s1 = s2 = 0.0
    for x in seg:
        s0 = x + c * s1 - s2
        s2 = s1
        s1 = s0
    real = s1 - s2 * math.cos(wq)
    imag = s2 * math.sin(wq)
    return math.sqrt(real * real + imag * imag)


def level_at(seg, freq, win=60.0):
    """某频率附近的峰值电平（线性）。"""
    best = 0.0
    f = max(100.0, freq - win)
    while f <= freq + win:
        v = goertzel(seg, f)
        if v > best:
            best = v
        f += 5.0
    return best


def synthesize(f0, formants, seconds):
    n = int(SR * seconds)
    flow = glottal_flow(f0, n)
    # 辐射出去的是声门流的**导数**（唇端辐射 +6 dB/oct）
    rad = [0.0] * n
    for i in range(1, n):
        rad[i] = flow[i] - flow[i - 1]

    # 级联三个共振器 —— 得到频谱的**形状**
    y = rad
    bws = [60.0, 90.0, 120.0]
    for k, f in enumerate(formants):
        y = resonator(y, f, bws[k])

    # 峰值均衡 —— 把三个共振峰拉到真实比例
    seg = y[SR // 2: SR // 2 + 4096]
    cur = [level_at(seg, f) for f in formants]
    for k, f in enumerate(formants):
        want = cur[0] * (10.0 ** (LEVELS_DB[k] / 20.0))
        gain = 20.0 * math.log10((want + 1e-12) / (cur[k] + 1e-12))
        y = peaking_eq(y, f, gain, q=6.0)
        cur[k] = want

    peak = max(abs(v) for v in y) or 1.0
    return [v / peak for v in y]


def report(y, formants):
    seg = y[SR // 2: SR // 2 + 4096]
    levels = [level_at(seg, f) for f in formants]
    mx = max(levels)
    print("合成频谱（独立测量，不经过 App 代码）：")
    for f, lv in zip(formants, levels):
        print(f"    目标 {f:6.0f} Hz   {20 * math.log10(lv / mx + 1e-12):+6.1f} dB")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    name = args[0] if args else "a"
    seconds = float(args[1]) if len(args) > 1 else 3.0
    f0, formants = VOWELS[name]

    y = synthesize(f0, formants, seconds)
    if check:
        report(y, formants)
        return

    frames = bytearray()
    for v in y:
        s = int(max(-1.0, min(1.0, v * 0.6)) * 32767)
        frames += struct.pack("<h", s)

    sys.stdout.buffer.write(b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVE")
    sys.stdout.buffer.write(b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16))
    sys.stdout.buffer.write(b"data" + struct.pack("<I", len(frames)))
    sys.stdout.buffer.write(bytes(frames))


main()
