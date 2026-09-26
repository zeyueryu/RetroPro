#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 拉取构建所需的第三方大文件
#
#   sherpa-onnx 预编译 AAR → app/libs/   （约 50 MB，构建必需）
#
# ★ 语音模型（约 229 MB）**不再需要在这里拉取**：它已从 APK 中剥离，
#   改为应用运行时按需下载（我的 → 版本与更新 → 语音模型）。
#   构建不再依赖任何模型文件。
#
# 用法：
#   bash tools/fetch_deps.sh                 # 官方源
#   MIRROR=ghfast bash tools/fetch_deps.sh   # 走 ghfast.top 镜像（中国大陆推荐）
#
# 另：若想留一份模型做本地参考（非必需），可显式加 --models。
# ---------------------------------------------------------------------------
set -euo pipefail

SHERPA_VERSION="1.13.8"
SENSE_VOICE_ARCHIVE="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
SILERO_FILE="silero_vad.onnx"

GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
AAR_URL="${GH}/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
MODEL_URL="${GH}/asr-models/${SENSE_VOICE_ARCHIVE}"
VAD_URL="${GH}/asr-models/${SILERO_FILE}"

WITH_MODELS=0
for arg in "$@"; do
    case "$arg" in
        --models) WITH_MODELS=1 ;;
        *) echo "未知参数：$arg（可用：--models）" >&2; exit 2 ;;
    esac
done

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
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$LIBS_DIR"

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

# ---- 构建必需：sherpa-onnx AAR ----
fetch "$AAR_URL" "$LIBS_DIR/sherpa-onnx-${SHERPA_VERSION}.aar"

if [ "$WITH_MODELS" = "1" ]; then
    # ---- 可选：模型本地留档（构建不读它，仅供人工核对）----
    mkdir -p "$MODELS_DIR"
    fetch "$VAD_URL" "$MODELS_DIR/$SILERO_FILE"
    fetch "$MODEL_URL" "$TMP_DIR/$SENSE_VOICE_ARCHIVE"

    echo "解包 SenseVoice 模型（约 1 GB 压缩包，需要几分钟）…"
    tar -xjf "$TMP_DIR/$SENSE_VOICE_ARCHIVE" -C "$TMP_DIR"
    EXTRACTED="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'sherpa-onnx-sense-voice*' | head -1)"
    [ -n "$EXTRACTED" ] || { echo "解包结果中找不到模型目录" >&2; exit 1; }
    for f in "model.int8.onnx" "tokens.txt"; do
        [ -f "$EXTRACTED/$f" ] || { echo "压缩包内缺少 $f，请检查上游是否改版" >&2; exit 1; }
        cp -f "$EXTRACTED/$f" "$MODELS_DIR/$f"
    done
    echo "模型已留档在 $MODELS_DIR（不参与构建）"
fi

echo
echo "完成。产物："
ls -lh "$LIBS_DIR/sherpa-onnx-${SHERPA_VERSION}.aar" 2>/dev/null || true
echo
echo "现在可以：./gradlew :app:assembleDebug   （或 gradle :app:assembleDebug）"
echo "语音模型由 App 运行时下载：我的 → 版本与更新 → 语音模型"
