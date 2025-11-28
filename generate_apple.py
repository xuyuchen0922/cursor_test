#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成苹果图片的脚本
"""
from PIL import Image, ImageDraw
import os

# 创建一张白色背景的图片
width, height = 400, 400
image = Image.new('RGB', (width, height), color='white')
draw = ImageDraw.Draw(image)

# 绘制苹果主体（红色圆形）
apple_center_x, apple_center_y = width // 2, height // 2 + 20
apple_radius = 120

# 绘制苹果主体（椭圆形，稍微扁一点）
draw.ellipse(
    [
        apple_center_x - apple_radius,
        apple_center_y - apple_radius * 0.9,
        apple_center_x + apple_radius,
        apple_center_y + apple_radius * 1.1
    ],
    fill='#FF4444',
    outline='#CC0000',
    width=3
)

# 绘制苹果的叶子（绿色）
leaf_x = apple_center_x + 40
leaf_y = apple_center_y - apple_radius * 0.9 - 10
draw.ellipse(
    [leaf_x - 15, leaf_y - 20, leaf_x + 15, leaf_y + 5],
    fill='#44AA44',
    outline='#228822',
    width=2
)

# 绘制苹果的茎（棕色）
stem_x = apple_center_x + 20
stem_y = apple_center_y - apple_radius * 0.9
draw.rectangle(
    [stem_x - 3, stem_y - 15, stem_x + 3, stem_y],
    fill='#8B4513',
    outline='#654321',
    width=1
)

# 添加高光效果（让苹果看起来更立体）
highlight_x = apple_center_x - 40
highlight_y = apple_center_y - 30
draw.ellipse(
    [highlight_x - 30, highlight_y - 25, highlight_x + 10, highlight_y + 15],
    fill='#FF8888',
    outline=None
)

# 保存图片
output_path = 'apple.png'
image.save(output_path, 'PNG')
print(f'苹果图片已生成: {output_path}')
