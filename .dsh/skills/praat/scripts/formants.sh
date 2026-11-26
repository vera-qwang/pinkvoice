#!/bin/bash
# 用真 Praat 分析语音的共振峰。
#
# 用法:
#   formants.sh <音频.wav>                    导出表格 + 有声段统计
#   formants.sh <音频.wav> --csv <输出.csv>   只导出表格（每 12.5 ms 一行）
#   formants.sh <a.wav> <b.wav>               对照两份（比如 App vs Praat）
#
# ## 为什么要这个脚本
#
# 分析实现准不准，只有跟真 Praat 对过才知道。
# 之前「App 的 F1 整段丢失」的问题就是靠它才看清的 ——
# 之前所有判断都靠 Python 复刻 LPC，复刻错了就一起错。
#
# ## 输出怎么读
#
# **统计量只在有声段上算。** Praat 在静音段会给出乱七八糟的高共振峰
# （实测 20 秒录音整段中位数 F1=1499，有声段是 529，差近 1000 Hz）。
# 脚本按 RMS 自动找出有声段。
#
set -u

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
PR="/Applications/Praat.app/Contents/MacOS/Praat"
PY=/usr/bin/python3

die() { echo "$*" >&2; exit 1; }

[ -x "$PR" ] || die "找不到 Praat: ${PR}"
[ -x "$PY" ] || die "找不到 python3: ${PY}"

# ---- 参数 ----
# 实际的解析和统计都在下面的 python 里（Praat 脚本要生成，交给 python 更清楚）

# ---- 统计 ----
"$PY" - "$@" <<'PYEOF'
import sys, os, wave, subprocess, tempfile
import numpy as np

args = [a for a in sys.argv[1:]]
csv_out = None
wavs = []
i = 0
while i < len(args):
    if args[i] == '--csv':
        csv_out = args[i+1] if i+1 < len(args) else None
        i += 2
        continue
    wavs.append(args[i]); i += 1

PR = "/Applications/Praat.app/Contents/MacOS/Praat"

def export(wav, out):
    wav = os.path.abspath(wav)
    script = tempfile.NamedTemporaryFile('w', suffix='.praat', delete=False)
    script.write(f'''Read from file: "{wav}"
To Formant (burg): 0, 5, 5500, 0.025, 50
Rename: "f"
writeFileLine: "{out}", "Time_s F1_Hz F2_Hz F3_Hz F4_Hz"
t = 0
while t < 600
    f1 = Get value at time: 1, t, "Hertz", "linear"
    if f1 = undefined
        f1 = 0
    endif
    f2 = Get value at time: 2, t, "Hertz", "linear"
    if f2 = undefined
        f2 = 0
    endif
    f3 = Get value at time: 3, t, "Hertz", "linear"
    if f3 = undefined
        f3 = 0
    endif
    f4 = Get value at time: 4, t, "Hertz", "linear"
    if f4 = undefined
        f4 = 0
    endif
    appendFileLine: "{out}", fixed$(t, 6), " ", fixed$(f1, 2), " ", fixed$(f2, 2), " ", fixed$(f3, 2), " ", fixed$(f4, 2)
    t = t + 0.0125
endwhile
''')
    script.close()
    subprocess.run([PR, '--hide-picture', '--no-pref-files', '--run', script.name],
                   capture_output=True)
    os.unlink(script.name)

def read_table(path):
    rows = []
    for line in open(path):
        p = line.split()
        if len(p) < 5 or p[0] == 'Time_s':
            continue
        try:
            rows.append([float(x) for x in p[:5]])
        except ValueError:
            pass
    return np.array(rows) if rows else np.zeros((0, 5))

def voiced_range(path):
    """按 RMS 找出有声段，返回 (起, 止) 秒。"""
    w = wave.open(path, 'rb')
    n, sr = w.getnframes(), w.getframerate()
    x = np.frombuffer(w.readframes(n), dtype=np.int16).astype(float) / 32768.0
    w.close()
    if x.ndim > 1:
        x = x.reshape(-1, x.shape[1]).mean(axis=1)
    fr = max(1, int(sr * 0.05)); hop = max(1, int(sr * 0.025))
    m = len(x) // hop
    if m < 2:
        return 0.0, 0.0
    rms = np.array([np.sqrt(np.mean(x[i*hop:i*hop+fr]**2)) for i in range(m - 1)])
    thr = rms.max() * 0.25
    on = rms > thr
    segs, cur = [], None
    for k, v in enumerate(on):
        if v and cur is None:
            cur = k
        elif not v and cur is not None:
            if k - cur > 4: segs.append((cur, k))
            cur = None
    if cur is not None: segs.append((cur, len(on)))
    if not segs:
        return 0.0, 0.0
    a = segs[0][0] * hop / sr
    b = segs[-1][1] * hop / sr
    return a, b

def stats(path):
    out = os.path.join(tempfile.gettempdir(), 'praat_f_' + os.path.basename(path) + '.txt')
    export(path, out)
    a = read_table(out)
    if csv_out:
        import shutil; shutil.copy(out, csv_out)
    if len(a) == 0:
        return None, None, None
    t0, t1 = voiced_range(path)
    v = a[(a[:, 0] >= t0) & (a[:, 0] <= t1)]
    v = v[v[:, 1] > 0]
    if len(v) == 0:
        return None, None, None
    return v, (t0, t1), out

results = []
for wav in wavs:
    v, rng, tbl = stats(wav)
    if v is None:
        print(f'{wav}: 没有有效帧')
        continue
    results.append((wav, v, rng))
    f1, f2, f3 = v[:, 1], v[:, 2], v[:, 3]
    print(f'{os.path.basename(wav)}   有声段 {rng[0]:.2f}–{rng[1]:.2f}s（{len(v)} 帧）')
    print(f'  F1  中位 {np.median(f1):6.0f}  σ {np.std(f1):5.0f}  范围 {f1.min():5.0f}–{f1.max():5.0f}')
    print(f'  F2  中位 {np.median(f2):6.0f}  σ {np.std(f2):5.0f}  范围 {f2.min():5.0f}–{f2.max():5.0f}')
    print(f'  F3  中位 {np.median(f3):6.0f}')

if len(results) == 2:
    print()
    print('对照:')
    (n1, v1, _), (n2, v2, _) = results
    print(f'  {"":22s} {"F1":>8s} {"F2":>8s}')
    print(f'  {os.path.basename(n1):22s} {np.median(v1[:,1]):8.0f} {np.median(v1[:,2]):8.0f}')
    print(f'  {os.path.basename(n2):22s} {np.median(v2[:,1]):8.0f} {np.median(v2[:,2]):8.0f}')
    print(f'  {"差":22s} {np.median(v2[:,1])-np.median(v1[:,1]):+8.0f} {np.median(v2[:,2])-np.median(v1[:,2]):+8.0f}')
PYEOF
