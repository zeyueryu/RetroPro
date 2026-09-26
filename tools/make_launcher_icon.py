"""从一张方形图源生成 Android 自适应图标（adaptive icon）资源。

用法：
    python tools/make_launcher_icon.py --src <图源.png> [--name ic_launcher]
                                       [--width-dp 56] [--verify 输出.png]

## 自适应图标的规格（已核实，改动前先读）

- 画布 **108×108dp**；前景层与背景层都必须按 108dp 出图。
- 启动器会给合成结果套遮罩，**可见区为 72×72dp**。
- **安全区是中心 66dp 直径的圆** —— 因为各家遮罩"从中心最多只延伸 33dp"，
  所以落在 r=33dp 圆内的内容保证不被任何遮罩裁掉。
- 前景 PNG 各密度画布尺寸：mdpi 108 / hdpi 162 / xhdpi 216 / xxhdpi 324 / xxxhdpi 432。

## 为什么按"宽高比"反算宽度

本脚本把标志当作**居中矩形**来摆放，约束是矩形的外接圆不超过安全区：

    sqrt((W/2)^2 + (H/2)^2) <= 33dp   →   W <= 33 * 2 / sqrt(1 + 1/aspect^2)

宽字标（如 aspect≈1.92）的宽度就是限制项 —— 直接按宽度顶满会切掉左右两端。
脚本会打印实测的最大半径并与 33dp 对比，**必须确认通过**。

## 图源要求

- 方形、分辨率越高越好（≥512px，推荐 1024+）。
- **标志居中**、背景为纯色或近似纯色（脚本取边缘环中位数当背景色）。
- 标志与背景要有足够对比度（脚本按亮度阈值反算 alpha）。
- 输出前景为**白字 + 透明底**；如需保留原色，改 `_build_logo()` 里的 RGB 填充。
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

CANVAS_DP = 108.0
SAFE_R_DP = 33.0  # 安全区半径（中心 66dp 直径圆的半径）
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

ADAPTIVE_XML = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background" />\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
    "</adaptive-icon>\n"
)


def _background_color(arr: np.ndarray, ring: int = 60) -> np.ndarray:
    """边缘环的中位数 —— 不受居中标志影响。"""
    border = np.concatenate(
        [
            arr[:ring, :, :].reshape(-1, 3),
            arr[-ring:, :, :].reshape(-1, 3),
            arr[:, :ring, :].reshape(-1, 3),
            arr[:, -ring:, :].reshape(-1, 3),
        ]
    )
    return np.median(border, axis=0)


def _build_logo(arr: np.ndarray) -> Image.Image:
    """亮度反算 alpha，得到白字 + 透明底的标志图（紧贴边界裁切）。"""
    lum = arr.mean(axis=2)
    bg_lum = float(_background_color(arr).mean())
    white_lum = float(lum.max())
    # 抬高下限，把背景噪声（通常 ±5）压成全透明
    floor = bg_lum + 14.0
    alpha = np.clip((lum - floor) / max(1.0, white_lum - floor), 0.0, 1.0)

    ys, xs = np.nonzero(alpha > 0.08)  # 阈值放宽，保住抗锯齿边缘
    if len(xs) == 0:
        raise SystemExit("未能在图源中识别出标志：请确认标志与背景有足够对比度")
    crop = alpha[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1]

    h, w = crop.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    # RGB 全部填白（含透明区）：逐通道独立缩放时不会产生暗边光晕
    rgba[..., :3] = 255
    rgba[..., 3] = (crop * 255.0).round().astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")


def _downscale(img: Image.Image, target_w: int, aspect: float) -> Image.Image:
    """分步缩小：一次 LANCZOS 缩 20+ 倍会走样。"""
    while img.width > target_w * 3:
        half = max(target_w, img.width // 2)
        img = img.resize((half, max(1, round(half / aspect))), Image.LANCZOS)
    return img.resize((target_w, max(1, round(target_w / aspect))), Image.LANCZOS)


def _mask(size: int, shape: str, visible_px: int) -> Image.Image:
    """4x 超采样绘制遮罩，得到平滑边缘。"""
    s = 4
    n = size * s
    big = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(big)
    lo = (n - visible_px * s) // 2
    box = (lo, lo, lo + visible_px * s, lo + visible_px * s)
    if shape == "circle":
        d.ellipse(box, fill=255)
    elif shape == "squircle":
        d.rounded_rectangle(box, radius=int(visible_px * s * 0.42), fill=255)
    elif shape == "rounded":
        d.rounded_rectangle(box, radius=int(visible_px * s * 0.18), fill=255)
    else:
        d.rectangle((0, 0, n, n), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def _verify_sheet(fg: Image.Image, bg_rgb: tuple[int, int, int], out: Path) -> bool:
    canvas = fg.width
    visible = int(round(72 * canvas / CANVAS_DP))
    bg = Image.new("RGBA", (canvas, canvas), bg_rgb + (255,))
    composed = Image.alpha_composite(bg, fg)

    a = np.asarray(fg)[..., 3]
    ys, xs = np.nonzero(a > 8)
    c = canvas / 2.0
    r_max = float(np.max(np.sqrt((xs + 0.5 - c) ** 2 + (ys + 0.5 - c) ** 2)))
    safe_px = SAFE_R_DP * canvas / CANVAS_DP
    print(f"标志离中心最远 {r_max:.1f}px / 安全区 {safe_px:.1f}px")
    ok = r_max <= safe_px
    print("安全区检查:", "通过" if ok else "★ 不通过 —— 会被圆形遮罩裁切，请减小 --width-dp")

    shapes = ("full", "circle", "squircle", "rounded")
    pad = 16
    sheet = Image.new("RGB", (canvas * 2 + pad * 3, canvas * 2 + pad * 3), (250, 250, 250))
    for i, shape in enumerate(shapes):
        t = Image.new("RGBA", (canvas, canvas), (255, 255, 255, 255))
        t.paste(composed, (0, 0), _mask(canvas, shape, visible))
        ImageDraw.Draw(t).text((14, 10), shape, fill=(20, 20, 20, 255))
        sheet.paste(t, (pad + (i % 2) * (canvas + pad), pad + (i // 2) * (canvas + pad)))
    sheet.thumbnail((1000, 1000), Image.LANCZOS)
    sheet.save(out)
    print("验证图:", out)
    return ok


def main() -> None:
    ap = argparse.ArgumentParser(description="生成 Android 自适应图标资源")
    ap.add_argument("--src", required=True, help="方形图源路径")
    ap.add_argument("--name", default="ic_launcher", help="资源名（默认 ic_launcher）")
    ap.add_argument("--width-dp", type=float, default=56.0, help="标志占 108dp 画布的宽度")
    ap.add_argument("--res", default=None, help="res 目录，默认 app/src/main/res")
    ap.add_argument("--verify", default=None, help="额外输出一张多遮罩验证图")
    args = ap.parse_args()

    project = Path(__file__).resolve().parent.parent
    res = Path(args.res) if args.res else project / "app" / "src" / "main" / "res"
    if not res.is_dir():
        raise SystemExit(f"找不到 res 目录: {res}")

    src = Image.open(args.src).convert("RGB")
    if src.width != src.height:
        print(f"警告：图源不是正方形（{src.width}x{src.height}），仍按居中截取处理")
    arr = np.asarray(src).astype(np.float32)

    bg = _background_color(arr)
    bg_hex = "#{:02X}{:02X}{:02X}".format(*(int(round(v)) for v in bg))
    print("背景色:", bg_hex)

    logo = _build_logo(arr)
    aspect = logo.width / logo.height
    print(f"标志尺寸: {logo.width}x{logo.height}  aspect={aspect:.4f}")

    limit = SAFE_R_DP * 2 / (1 + 1 / aspect**2) ** 0.5
    print(f"安全区允许的最大宽度: {limit:.1f}dp（当前 {args.width_dp}dp）")
    if args.width_dp > limit:
        print("★ 当前宽度超出安全区，圆形遮罩会裁掉两端")

    for name, scale in DENSITIES.items():
        canvas_px = int(round(CANVAS_DP * scale))
        target_w = int(round(args.width_dp * scale))
        img = _downscale(logo, target_w, aspect)
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        canvas.paste(img, ((canvas_px - img.width) // 2, (canvas_px - img.height) // 2), img)
        out_dir = res / f"mipmap-{name}"
        out_dir.mkdir(parents=True, exist_ok=True)
        canvas.save(out_dir / f"{args.name}_foreground.png")
        print(f"  {name:8s} canvas={canvas_px:3d}px logo={img.width}x{img.height}px")

    anydpi = res / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for fname in (f"{args.name}.xml", f"{args.name}_round.xml"):
        (anydpi / fname).write_text(ADAPTIVE_XML, encoding="utf-8")
        print("  wrote", fname)

    colors = res / "values" / "colors.xml"
    colors.parent.mkdir(parents=True, exist_ok=True)
    existing = colors.read_text(encoding="utf-8") if colors.exists() else ""
    entry = f'    <color name="ic_launcher_background">{bg_hex}</color>\n'
    if "ic_launcher_background" in existing:
        # 替换已有条目，保留文件里其它颜色
        import re

        existing = re.sub(
            r'[ \t]*<color name="ic_launcher_background">.*?</color>\n', entry, existing
        )
        colors.write_text(existing, encoding="utf-8")
    else:
        colors.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + entry + "</resources>\n",
            encoding="utf-8",
        )
    print("  colors.xml ->", bg_hex)

    print()
    print("下一步（手动，只做一次）：")
    print("  1. AndroidManifest 的 <application> 加：")
    print('       android:icon="@mipmap/ic_launcher"')
    print('       android:roundIcon="@mipmap/ic_launcher_round"')
    print("  2. 删掉旧的 @drawable/ic_launcher（若有）")

    if args.verify:
        print()
        _verify_sheet(
            Image.open(res / "mipmap-xxxhdpi" / f"{args.name}_foreground.png").convert("RGBA"),
            tuple(int(round(v)) for v in bg),
            Path(args.verify),
        )


if __name__ == "__main__":
    main()
