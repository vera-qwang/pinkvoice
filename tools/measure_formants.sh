#!/bin/bash
# 真机测量：共振峰准确度 + 稳定度
#
# 关键：Mac 的输出音量会自己掉（测着测着从 100 掉到 38/69），
# 每次测量前都要把它设回 100 —— 否则量到的全是噪声。
set -u
SDK="$HOME/Library/Android/sdk"
DEV="${1:?用法: measure.sh <device-serial> [元音...]}"
shift || true
VOWELS="${*:-a i u}"

A="$SDK/platform-tools/adb -s $DEV"

fix_volume() {
  osascript -e "set volume output volume 100" 2>/dev/null
}

dump_formants() {
  $A shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  $A shell cat /sdcard/u.xml 2>/dev/null | tr '>' '\n' |
    grep -oE 'text="(F1 [0-9]+ Hz|F2 [0-9]+ Hz|读数不稳)"' |
    sed 's/text="//; s/"$//'
}

echo "设备: $DEV"
echo "目标值（参考位置）: a=730/1090  i=270/2290  u=300/870"
echo

for V in $VOWELS; do
  fix_volume
  python3 tools/gen_vowel.py "$V" 6 > "/tmp/m_$V.wav" 2>/dev/null
  ( for i in $(seq 1 60); do afplay "/tmp/m_$V.wav" 2>/dev/null; done ) &
  AF=$!
  sleep 2.5

  echo "=== /$V/ ==="
  for k in $(seq 1 10); do
    dump_formants | tr '\n' ' '
    echo ""
    sleep 0.7
  done
  kill $AF 2>/dev/null
  wait $AF 2>/dev/null
  echo
done
