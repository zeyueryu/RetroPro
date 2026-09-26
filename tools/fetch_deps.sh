#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 拉取构建所需的第三方大文件（不入库，见 .gitignore）
#
#   1. sherpa-onnx 预编译 AAR        → app/libs/          (~50 MB)
#   2. SenseVoice-Small int8 模型    → models/ 与 app/src/main/assets/asr/
#   3. Silero VAD 模型               → 同上                          (~0.6 MB)
#
# 用法：
#   bash tools/fetch_deps.sh              # 官方源
#   MIRROR=ghfast bash tools/fetch_deps.sh  # 走 ghfast.top 镜像（中国大陆推荐）
#
# 校验：脚本末尾会用固定字节数核对下载结果，与 upstream 不一致会告警。
# ---------------------------------------------------------------------------
set -euo pipefail

SHERPA_VERSION="1.13.8"
SENSE_VOICE_ARCHIVE="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
SILERO_FILE="silero_vad.onnx"

GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
AAR_URL="${GH}/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
MODEL_URL="${GH}/asr-models/${SENSE_VOICE_ARCHIVE}"
VAD_URL="${GH}/asr-models/${SILERO_FILE}"

# 中国大陆直连 github.com 常被中断；ghfast.top 是可用的 release 资产代理
if [ "${MIRROR:-}" = "ghfast" ]; then
    AAR_URL="https://ghfast.top/${AAR_URL}"
    MODEL_URL="https://ghfast.top/${MODEL_URL}"
    VAD_URL="https://ghfast.top/${VAD_URL}"
    echo "使用镜像：ghfast.top"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/app/libs"
MODELS_DIR="$ROOT/models"
ASSETS_DIR="$ROOT/app/src/main/assets/asr"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$LIBS_DIR" "$MODELS_DIR" "$ASSETS_DIR"

fetch() { # fetch <url> <输出文件>
    local url="$1" out="$2"
    if [ -s "$out" ]; then
        echo "已存在，跳过：$(basename "$out")"
        return 0
    fi
    echo "下载 $(basename "$out") …"
    if ! curl -fL --retry 3 --retry-delay 3 -o "$out.part" "$url"; then
        echo "  下载失败：$url" >&2
        echo "  提示：重试时加 MIRROR=ghfast，或手动下载后放到对应目录。" >&2
        rm -f "$out.part"
        return 1
    fi
    mv "$out.part" "$out"
}

# ---- 1. sherpa-onnx AAR（约 50 MB，超出 GitHub 单文件 100 MB 限制之外但过大不适合入库）----
fetch "$AAR_URL" "$LIBS_DIR/sherpa-onnx-${SHERPA_VERSION}.aar"

# ---- 2 & 3. 模型 ----
fetch "$VAD_URL" "$MODELS_DIR/$SILERO_FILE"
fetch "$MODEL_URL" "$TMP_DIR/$SENSE_VOICE_ARCHIVE"

echo "解包 SenseVoice 模型（约 1 GB 压缩包，需要几分钟）…"
tar -xjf "$TMP_DIR/$SENSE_VOICE_ARCHIVE" -C "$TMP_DIR"
EXTRACTED="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'sherpa-onnx-sense-voice*' | head -1)"
[ -n "$EXTRACTED" ] || { echo "解包结果中找不到模型目录" >&2; exit 1; }

# 只保留运行时需要的三个文件
for f in "model.int8.onnx" "tokens.txt"; do
    src="$EXTRACTED/$f"
    if [ ! -f "$src" ]; then
        echo "压缩包内缺少 $f，请检查上游是否改版" >&2
        exit 1
    fi
    cp -f "$src" "$MODELS_DIR/$f"
    cp -f "$src" "$ASSETS_DIR/$f"
done
cp -f "$MODELS_DIR/$SILERO_FILE" "$ASSETS_DIR/$SILERO_FILE"

echo
echo "完成。产物："
ls -lh "$LIBS_DIR/sherpa-onnx-${SHERPA_VERSION}.aar" 2>/dev/null || true
ls -lh "$ASSETS_DIR"
echo
echo "现在可以：./gradlew :app:assembleDebug   （或 gradle :app:assembleDebug）"
