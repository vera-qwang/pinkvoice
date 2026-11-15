#!/usr/bin/env bash
#
# 把发布用的 keystore 配成 GitHub Secrets，让 CI 用正式签名构建 Release APK。
#
# 用法：
#     ./scripts/setup-release-secrets.sh /path/to/release.keystore
#
# 会做三件事：
#   1. 设 4 个 Secret（keystore base64 + 口令 + 别名）
#   2. 删掉早期的 "latest" 滚动预发布（已改成只按标签发布）
#   3. 打印验证方法
#
# 需要 gh 已登录（gh auth login）且对仓库有写权限。

set -euo pipefail

KEYSTORE="${1:-}"
REPO="${2:-}"

if [ -z "$KEYSTORE" ]; then
  echo "用法: $0 <keystore 路径> [仓库名]" >&2
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "找不到 keystore: $KEYSTORE" >&2
  exit 1
fi

# 口令：优先用环境变量，否则交互式问（不回显）
if [ -z "${PINKVOICE_STORE_PASSWORD:-}" ]; then
  read -r -s -p "密钥库口令: " PINKVOICE_STORE_PASSWORD
  echo
fi

ALIAS="${PINKVOICE_KEY_ALIAS:-pinkvoice}"
KEY_PASSWORD="${PINKVOICE_KEY_PASSWORD:-$PINKVOICE_STORE_PASSWORD}"

if ! command -v gh >/dev/null 2>&1; then
  echo "找不到 gh，先装一个：brew install gh" >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "gh 没登录，先执行：gh auth login" >&2
  exit 1
fi

GH_ARGS=()
if [ -n "$REPO" ]; then
  GH_ARGS=(--repo "$REPO")
fi

echo "==> 设置 Secret: PINKVOICE_KEYSTORE_BASE64"
base64 -i "$KEYSTORE" | tr -d '\n' | gh secret set PINKVOICE_KEYSTORE_BASE64 "${GH_ARGS[@]}"

echo "==> 设置 Secret: PINKVOICE_STORE_PASSWORD"
gh secret set PINKVOICE_STORE_PASSWORD --body "$PINKVOICE_STORE_PASSWORD" "${GH_ARGS[@]}"

echo "==> 设置 Secret: PINKVOICE_KEY_ALIAS"
gh secret set PINKVOICE_KEY_ALIAS --body "$ALIAS" "${GH_ARGS[@]}"

echo "==> 设置 Secret: PINKVOICE_KEY_PASSWORD"
gh secret set PINKVOICE_KEY_PASSWORD --body "$KEY_PASSWORD" "${GH_ARGS[@]}"

echo "==> 已设置的 Secret:"
gh secret list "${GH_ARGS[@]}" | grep -E 'PINKVOICE_' || true

# 早期的"latest"滚动预发布已经不用了（改成只按标签发布），顺手清掉
if gh release view latest "${GH_ARGS[@]}" >/dev/null 2>&1; then
  echo "==> 删除遗留的 latest 预发布"
  gh release delete latest --yes --cleanup-tag "${GH_ARGS[@]}" || true
fi

cat <<'EOF'

完成。下次打标签就会用正式签名：

    git tag v0.1.1
    git push origin v0.1.1

验证签名是否换成正式密钥：

    ./scripts/verify-apk-signing.sh

提示：keystore 丢了的话，**已发布的 APK 就再也装不上了**
（系统认签名，换签名只能卸载重装，App 数据会丢）。
请把它备份到密码管理器或安全硬盘。
EOF
