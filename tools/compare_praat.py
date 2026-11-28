#!/usr/bin/env python3
"""App 分析层 vs 真 Praat 的逐帧对照。

用法：compare_praat.py <app_frames.txt> <praat_frames.txt>

App 的帧时间 = 窗中心；Praat 的帧时间也是窗中心。
两边帧网格不同，按时间就近取最近的一帧比。
"""
import sys


def load(path):
    rows = []
    with open(path, errors="replace") as fh:
        for line in fh:
            p = line.split()
            if len(p) < 4 or not p[0][0].isdigit():
                continue
            try:
                rows.append((float(p[0]), float(p[1]), float(p[2]), float(p[3])))
            except ValueError:
                continue
    return rows


def main():
    app = load(sys.argv[1])
    praat = load(sys.argv[2])
    label = sys.argv[3] if len(sys.argv) > 3 else ""

    # 只在两边都有信号的帧上比
    app_v = [r for r in app if r[1] > 0 and r[2] > 0]
    pr_v = [r for r in praat if r[1] > 0 and r[2] > 0]
    if not app_v or not pr_v:
        print("没有有效帧")
        return

    times = [r[0] for r in pr_v]
    e1, e2, e3 = [], [], []
    pairs = []
    for t, a1, a2, a3 in app_v:
        # 就近取 Praat 的帧
        i = min(range(len(times)), key=lambda k: abs(times[k] - t))
        _, p1, p2, p3 = pr_v[i]
        if abs(times[i] - t) > 0.03:
            continue
        e1.append(abs(a1 - p1))
        e2.append(abs(a2 - p2))
        e3.append(abs(a3 - p3))
        pairs.append((t, a1, a2, p1, p2))

    def med(v):
        s = sorted(v)
        return s[len(s) // 2]

    def mean(v):
        return sum(v) / len(v)

    print(f"=== {label} ===")
    print(f"可比帧 {len(e1)}（App {len(app_v)} / Praat {len(pr_v)}）")
    print(f"|ΔF1| 中位 {med(e1):6.1f}  平均 {mean(e1):6.1f}")
    print(f"|ΔF2| 中位 {med(e2):6.1f}  平均 {mean(e2):6.1f}")
    print(f"|ΔF3| 中位 {med(e3):6.1f}  平均 {mean(e3):6.1f}")
    for t, a1, a2, p1, p2 in pairs[:6]:
        print(f"   t={t:6.3f}  App {a1:7.1f}/{a2:<7.1f}  Praat {p1:7.1f}/{p2:<7.1f}")


if __name__ == "__main__":
    main()
