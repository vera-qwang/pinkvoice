#!/usr/bin/env bash
# 真机端到端对照：App 的显示值 vs 参照工具对同一段音频的分析。
#
# 用法：tools/device_formant_parity.sh [秒数]
#
# 步骤：
#   1. 启动 App → 进 F1/F2 页 → 开始监听
#   2. 录 N 秒（期间用户说话）
#   3. dump 出 App 录到的**原始**音频
#   4. 用参照工具分析同一段音频
#   5. 按墙钟时间对齐，逐帧比较
#
# 前提：手机开着「无线调试」，且已用 adb connect 连上。
set -euo pipefail

SECS="${1:-25}"
ADB="${ADB:-~/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-/tmp/pv_parity}"
mkdir -p "$OUT"

# ---- 找设备 ----
SERIAL="$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$SERIAL" ]; then
    echo "找不到设备。先跑：adb devices" >&2
    exit 1
fi
echo "设备: $SERIAL"

# ---- 启动 ----
$ADB -s "$SERIAL" shell am force-stop com.vera.pinkvoice
$ADB -s "$SERIAL" logcat -c
$ADB -s "$SERIAL" shell am start -n com.vera.pinkvoice/.MainActivity >/dev/null
sleep 6
$ADB -s "$SERIAL" shell input tap 1064 2936   # F1/F2 标签
sleep 2
$ADB -s "$SERIAL" shell input tap 751 2695    # 开始监听
sleep 2

# ---- 录制 ----
$ADB -s "$SERIAL" logcat -v time > "$OUT/app.log" 2>&1 &
LOGPID=$!
echo "录 $SECS 秒 —— 请对着手机说话（手机离嘴 15–20 cm，连续发元音拖长）"
sleep "$SECS"
$ADB -s "$SERIAL" shell am broadcast -a com.vera.pinkvoice.DUMP_AUDIO >/dev/null
sleep 3
kill $LOGPID 2>/dev/null || true

DUMP="$($ADB -s "$SERIAL" logcat -d -s PinkVoiceDiag:* 2>&1 \
        | grep -oE 'DUMP [^ ]+' | tail -1 | cut -d' ' -f2)"
if [ -z "$DUMP" ]; then
    echo "没有拿到 dump" >&2
    exit 1
fi
$ADB -s "$SERIAL" pull "$DUMP" "$OUT/app.wav" >/dev/null
echo "音频: $OUT/app.wav"

# ---- 参照工具 ----
PRAAT="${PRAAT:-/Applications/Praat.app/Contents/MacOS/Praat}"
cat > "$OUT/analyze.praat" <<PRAAT
Read from file: "$OUT/app.wav"
To Formant (burg): 0, 5, 5500, 0.025, 50
Rename: "f"
writeFileLine: "$OUT/praat.txt", "T F1 F2 F3"
t = 0
while t < 30.0
    f1 = Get value at time: 1, t, "Hertz", "linear"
    f2 = Get value at time: 2, t, "Hertz", "linear"
    f3 = Get value at time: 3, t, "Hertz", "linear"
    if f1 = undefined
        f1 = 0
    endif
    if f2 = undefined
        f2 = 0
    endif
    if f3 = undefined
        f3 = 0
    endif
    appendFileLine: "$OUT/praat.txt", fixed\$(t,6), " ", fixed\$(f1,2), " ", fixed\$(f2,2), " ", fixed\$(f3,2)
    t = t + 0.005
endwhile
PRAAT
"$PRAAT" --hide-picture --no-pref-files --run "$OUT/analyze.praat" >/dev/null 2>&1
echo "参照工具输出: $OUT/praat.txt"

# ---- 对照 ----
/usr/bin/python3 "$HERE/tools/compare_device_parity.py" \
    "$OUT/app.log" "$OUT/praat.txt" "$OUT/app.wav"
