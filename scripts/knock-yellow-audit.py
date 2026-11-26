#!/usr/bin/env python3
#
# knock-yellow-audit.py — 「敲敲」UI 黄色面积占比审计
#
# 依据：docs/敲敲UI改版计划书-v1.1.md §1.2 配色逻辑（电光黄 #F2FF00 面积 ≤5%，
#   仅允许出现在「选中胶囊 / 主按钮 / 角标 / 品牌 logo」）与 §7.2-8（截图直方图抽查）。
#
# 判定：把截图转到 HSV，命中性黄（hue 50°–75°、sat ≥ 0.55、val ≥ 0.20）的像素
#   计为黄色面积；占比 = 黄色像素 / 全图像素。抗锯齿混色（黄→黑/白过渡）落在
#   HSV 边界内即计数，因此该口径偏保守（宁高勿低）。
#
# 用法：
#   scripts/knock-yellow-audit.py screenshots/knock-final-*.png
#   scripts/knock-yellow-audit.py --threshold 5 --md-out /tmp/yellow.md screenshots/*.png
#   scripts/knock-yellow-audit.py --quiet screenshots/knock-final-*.png   # 只打印超标页
#
# 退出码：0 = 全部达标；1 = 存在 ≥threshold 的页面；2 = 用法/读图错误。
import argparse
import glob
import os
import sys
from colorsys import rgb_to_hsv

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("需要 Pillow：python3 -m pip install pillow", file=sys.stderr)
    sys.exit(2)

# 电光黄基准 #F2FF00 的 HSV（hue≈59.5°, sat=1.0, val=1.0）
HUE_MIN, HUE_MAX = 50.0 / 360.0, 75.0 / 360.0
SAT_MIN = 0.55
VAL_MIN = 0.20


def yellow_ratio(path: str) -> float:
    with Image.open(path) as img:
        img = img.convert("RGB")
        width, height = img.size
        # 缩放采样到 ≤ 400px 宽，兼顾精度与速度（大面积纯色不受影响）
        if width > 400:
            scale = 400.0 / width
            img = img.resize((400, max(1, int(height * scale))), Image.BILINEAR)
        # Pillow ≥12 用 get_flattened_data（getdata 已废弃）
        if hasattr(img, "get_flattened_data"):
            pixels = list(img.get_flattened_data())
        else:  # 兼容旧 Pillow
            pixels = list(img.getdata())
    total = len(pixels)
    if total == 0:
        return 0.0
    hits = 0
    for r, g, b in pixels:
        h, s, v = rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        if HUE_MIN <= h <= HUE_MAX and s >= SAT_MIN and v >= VAL_MIN:
            hits += 1
    return 100.0 * hits / total


def main() -> int:
    parser = argparse.ArgumentParser(description="敲敲 UI 黄色面积占比审计")
    parser.add_argument("images", nargs="+", help="PNG 文件或 glob（支持 screenshots/*.png）")
    parser.add_argument("--threshold", type=float, default=5.0, help="占比阈值（%%，默认 5）")
    parser.add_argument("--md-out", help="把 Markdown 表格写入该文件")
    parser.add_argument("--quiet", action="store_true", help="只打印超阈值页面")
    args = parser.parse_args()

    files: list[str] = []
    for pattern in args.images:
        matched = sorted(glob.glob(pattern))
        files.extend(matched if matched else [pattern])
    files = [f for f in files if os.path.isfile(f)]
    if not files:
        print("没有可读图片", file=sys.stderr)
        return 2

    rows = []
    for path in files:
        try:
            pct = yellow_ratio(path)
        except Exception as exc:  # noqa: BLE001
            print(f"读图失败 {path}: {exc}", file=sys.stderr)
            continue
        rows.append((os.path.basename(path), pct))
    rows.sort(key=lambda item: item[1], reverse=True)

    over = [r for r in rows if r[1] >= args.threshold]
    if not args.quiet:
        print(f"{'文件':<58} {'黄色占比':>8}  判定")
        print("-" * 82)
        for name, pct in rows:
            print(f"{name:<58} {pct:7.2f}%  {'⚠️ 超标' if pct >= args.threshold else 'OK'}")
    print()
    print(f"共 {len(rows)} 张；阈值 {args.threshold:g}%；超标 {len(over)} 张")
    if over:
        print("超标页面：")
        for name, pct in over:
            print(f"  - {name}  {pct:.2f}%")

    if args.md_out:
        lines = [
            "| 截图 | 黄色面积占比 | 判定（阈值 5%） |",
            "|---|---:|---|",
        ]
        for name, pct in rows:
            lines.append(f"| `{name}` | {pct:.2f}% | {'⚠️ 超标' if pct >= args.threshold else '✅ 达标'} |")
        with open(args.md_out, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        print(f"Markdown 表格已写入 {args.md_out}")

    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main())