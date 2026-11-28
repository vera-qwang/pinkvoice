#!/usr/bin/env python3
"""真机端到端对照：App 显示值 vs 参照工具对同一段音频的分析。

用法：compare_device_parity.py <app.log> <praat.txt> <wav>

<App 的显示值> 和 <参照工具的分析> 分析的是**同一段音频**
（App 那份是从设备 dump 出来的原始 PCM）。

时间对齐：
- App 的 SHOW 日志带墙钟时刻（该帧**发布**的时刻）
- dump 的 WAV 末尾 = dump 那一刻
- 所以 WAV 覆盖 [dump - wav时长, dump]
- App 显示值还要减去「分析延迟 + 平滑窗」造成的滞后 —— 用互相关扫出来
"""
import re
import sys
import wave

import numpy as np


def load_app_log(path):
    shows = []
    dump_t = None
    for line in open(path, errors="replace"):
        m = re.search(
            r"(\d\d):(\d\d):(\d\d)\.(\d+).*SHOW f1=(\d+) f2=(\d+)", line
        )
        if m:
            t = (
                int(m.group(1)) * 3600
                + int(m.group(2)) * 60
                + int(m.group(3))
                + int(m.group(4)) / 1000.0
            )
            shows.append((t, int(m.group(5)), int(m.group(6))))
            continue
        m = re.search(r"(\d\d):(\d\d):(\d\d)\.(\d+).*DUMP ", line)
        if m:
            dump_t = (
                int(m.group(1)) * 3600
                + int(m.group(2)) * 60
                + int(m.group(3))
                + int(m.group(4)) / 1000.0
            )
    return shows, dump_t


def load_praat(path):
    rows = []
    for line in open(path, errors="replace"):
        p = line.split()
        if len(p) < 4 or p[0] == "T":
            continue
        try:
            rows.append((float(p[0]), float(p[1]), float(p[2]), float(p[3])))
        except ValueError:
            continue
    if not rows:
        return None
    t = np.array([r[0] for r in rows])
    f1 = np.array([r[1] for r in rows])
    f2 = np.array([r[2] for r in rows])
    # 去掉「没找到共振峰」的默认值（Praat 会给 1499 之类的填充值）
    real = (f1 > 0) & (f1 < 1400) & (f2 > 0) & (f2 < 3500)
    return t[real], f1[real], f2[real]


def main():
    app_log, praat_txt, wav = sys.argv[1:4]
    shows, dump_t = load_app_log(app_log)
    if dump_t is None:
        print("日志里没有 DUMP 记录")
        return

    w = wave.open(wav, "rb")
    dur = w.getnframes() / w.getframerate()
    print(f"dump 的音频 {dur:.2f} s → 覆盖 [dump-{dur:.2f}, dump]")

    pr = load_praat(praat_txt)
    if pr is None:
        print("参照工具没找到任何有效共振峰（录音太安静？）")
        return
    pt, p1, p2 = pr
    print(f"参照工具的有效帧 {len(pt)}（共 {dur*200:.0f} 帧）")
    if len(pt) < 20:
        print("有效帧太少 —— 录音质量不够，换个近一点的环境再试")
        return

    def pmed(wt, half=0.10):
        m = (pt >= wt - half) & (pt <= wt + half)
        if m.sum() < 2:
            return None
        return float(np.median(p1[m])), float(np.median(p2[m]))

    best = None
    for lag in np.arange(0, 1.5, 0.01):
        e1, e2, pairs = [], [], []
        for t, f1, f2 in shows:
            wt = t - (dump_t - dur) - lag
            if wt < 0 or wt > dur:
                continue
            pm = pmed(wt)
            if pm is None:
                continue
            e1.append(abs(f1 - pm[0]))
            e2.append(abs(f2 - pm[1]))
            pairs.append((f1, f2, pm[0], pm[1]))
        if len(e1) < 10:
            continue
        a1, a2 = float(np.mean(e1)), float(np.mean(e2))
        if best is None or a1 + a2 < best[0] + best[1]:
            best = (a1, a2, lag, pairs, e1, e2)

    if best is None:
        print("可比帧太少 —— 录音质量不够")
        return

    a1, a2, lag, pairs, e1, e2 = best
    print()
    print(f"最佳对齐：滞后 {lag:.2f} s，可比帧 {len(pairs)}")
    print(f"  |ΔF1| 中位 {np.median(e1):6.0f} Hz   平均 {a1:6.0f} Hz")
    print(f"  |ΔF2| 中位 {np.median(e2):6.0f} Hz   平均 {a2:6.0f} Hz")
    print()
    for p in pairs[:10]:
        print(
            f"  App {p[0]:5d}/{p[1]:<5d}   参照 {p[2]:6.0f}/{p[3]:<6.0f}"
            f"   Δ {p[0]-p[2]:+6.0f} / {p[1]-p[3]:+6.0f}"
        )


if __name__ == "__main__":
    main()
