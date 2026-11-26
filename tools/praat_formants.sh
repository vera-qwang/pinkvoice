#!/bin/bash
# 真机测量 / 离线分析用的 Praat 封装。
#
# 实际实现放在 skill 里（Praat 脚本的坑太多，放在 skill 目录里能随 DSH 一起维护）：
#
#   .dsh/skills/praat/scripts/formants.sh
#
# 用法:
#   praat_formants.sh <音频.wav>                    导出 + 有声段统计
#   praat_formants.sh <音频.wav> --csv <输出.csv>   只导出表格
#   praat_formants.sh <a.wav> <b.wav>               对照两份
#
set -u
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILL_SCRIPT="$SELF_DIR/../.dsh/skills/praat/scripts/formants.sh"

if [ -x "$SKILL_SCRIPT" ]; then
    exec "$SKILL_SCRIPT" "$@"
fi

echo "找不到 $SKILL_SCRIPT" >&2
echo "（它在 .dsh/skills/praat/scripts/formants.sh，属于仓库的一部分）" >&2
exit 1
