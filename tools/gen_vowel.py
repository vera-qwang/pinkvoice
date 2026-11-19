#!/usr/bin/env python3
"""生成合成元音的 wav（给真机验证用）。

用法: python3 gen_vowel.py /a/ > /tmp/vowel_a.wav
"""
import math
import struct
import sys

SR = 16000


def glottal_flow(f0, n):
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


VOWELS = {
    "a": (120.0, [730.0, 1090.0, 2440.0]),
    "i": (200.0, [270.0, 2290.0, 3010.0]),
    "u": (120.0, [300.0, 870.0, 2240.0]),
    "ae": (150.0, [660.0, 1720.0, 2410.0]),
}

name = sys.argv[1] if len(sys.argv) > 1 else "a"
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0
f0, formants = VOWELS[name]

n = int(SR * seconds)
flow = glottal_flow(f0, n)
rad = [0.0] * n
for i in range(1, n):
    rad[i] = flow[i] - flow[i - 1]

y = rad
bws = [60.0, 90.0, 120.0]
for k, f in enumerate(formants):
    y = resonator(y, f, bws[k])

peak = max(abs(v) for v in y) or 1.0
frames = bytearray()
for v in y:
    s = int(max(-1.0, min(1.0, v / peak * 0.6)) * 32767)
    frames += struct.pack("<h", s)

sys.stdout.buffer.write(b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVE")
sys.stdout.buffer.write(b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16))
sys.stdout.buffer.write(b"data" + struct.pack("<I", len(frames)))
sys.stdout.buffer.write(bytes(frames))
