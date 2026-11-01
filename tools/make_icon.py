#!/usr/bin/env python3.11
"""
生成「绿色圆形 + 白色星形快门光圈」启动图标。
- ic_launcher.png      : 传统图标（绿圆铺满，四角透明），供不支持自适应图标的 launcher 使用
- ic_launcher_fg.png   : 自适应图标前景（透明底 + 白星），内容收在 66/108 安全区内
"""
import math
import os
from PIL import Image, ImageDraw

GREEN = (52, 199, 89, 255)      # #34C759，明亮的系统绿
WHITE = (255, 255, 255, 255)

# 脚本位于 tools/，资源目录在其上一级
RES = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
)
BASE = os.path.join(RES, "mipmap-{}")

# (目录, 传统图标边长, 前景边长)
SIZES = [
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]


def star_points(cx, cy, outer, inner, rotation=-90.0):
    """四角星（星芒）顶点：外/内半径交替，共 8 点"""
    pts = []
    for i in range(8):
        r = outer if i % 2 == 0 else inner
        a = math.radians(rotation + i * 45.0)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def draw_star(draw, cx, cy, radius, color):
    """画星芒 + 中心圆点，构成快门光圈造型"""
    outer = radius
    inner = radius * 0.30          # 内凹比例越小，星芒越尖
    draw.polygon(star_points(cx, cy, outer, inner), fill=color)
    # 中心圆点：让星芒中心更饱满，形似光圈中心
    cr = radius * 0.20
    draw.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=color)


def make_legacy(size, path):
    """传统图标：绿色圆铺满整个画布（四角透明→外观为圆形）"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, size - 1, size - 1], fill=GREEN)
    draw_star(d, size / 2.0, size / 2.0, size * 0.30, WHITE)
    img.save(path)


def make_foreground(size, path):
    """自适应前景：透明底 + 白星；星芒直径控制在安全区 66/108 ≈ 0.61 内"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    safe = size * (66.0 / 108.0)
    draw_star(d, size / 2.0, size / 2.0, safe / 2.0, WHITE)
    img.save(path)


for folder, legacy, fg in SIZES:
    d = BASE.format(folder)
    make_legacy(legacy, os.path.join(d, "ic_launcher.png"))
    make_foreground(fg, os.path.join(d, "ic_launcher_fg.png"))
    print(f"{folder}: ic_launcher {legacy}px, fg {fg}px")
