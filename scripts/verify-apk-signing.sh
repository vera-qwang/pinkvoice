#!/usr/bin/env bash
#
# 检查 Releases 上发布的 APK 是不是用正式密钥签的。
#
# 用法：
#     ./scripts/verify-apk-signing.sh [标签]      # 默认检查 latest 标签的 Release
#
# 正式密钥的证书主体是 CN=PinkVoice；
# 如果看到 CN=Android Debug，说明 CI 还在用仓库内的兜底 keystore，
# 多半是 GitHub Secrets 没配好。

set -euo pipefail

TAG="${1:-latest}"
REPO="${2:-}"

GH_ARGS=()
if [ -n "$REPO" ]; then
  GH_ARGS=(--repo "$REPO")
fi

# 找 apksigner。
#
# macOS 上 ANDROID_HOME 经常没设（只有 ANDROID_SDK_ROOT 或默认路径），
# 而 'set -u' 下引用未定义变量会直接报错——所以每个引用都要带默认值。
#
# 取 build-tools 目录里版本号最高的那个。
find_apksigner() {
  local sdk
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] || continue
    [ -d "$sdk/build-tools" ] || continue
    local f
    f="$(ls -1 "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
    if [ -n "$f" ] && [ -x "$f" ]; then
      echo "$f"
      return 0
    fi
  done
  return 1
}

APKSIGNER="$(find_apksigner || true)"
if [ -z "$APKSIGNER" ]; then
  echo "找不到 apksigner，先装 Android SDK build-tools" >&2
  echo "（找过 ANDROID_HOME / ANDROID_SDK_ROOT / ~/Library/Android/sdk）" >&2
  exit 1
fi
echo "apksigner: $APKSIGNER"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 列出 $TAG 的附件"
URLS=$(gh release view "$TAG" ${GH_ARGS[@]+"${GH_ARGS[@]}"} --json assets --jq '.assets[].browserDownloadUrl')
if [ -z "$URLS" ]; then
  echo "  没有附件" >&2
  exit 1
fi

for url in $URLS; do
  name="$(basename "$url")"
  echo "==> 下载 $name"
  curl -sL -o "$TMP/$name" "$url"
  echo "==> 检查签名"
  "$APKSIGNER" verify --print-certs "$TMP/$name" 2>&1 | grep -E "certificate DN" | head -1
done

echo
echo "期望看到： certificate DN: CN=PinkVoice"
