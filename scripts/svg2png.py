#!/usr/bin/env python3
"""
SVG 转 PNG 工具
使用 Edge/Chrome 浏览器的 headless 模式将 SVG 转换为 PNG
"""
import os
import sys
import time
import subprocess
import shutil

def find_browser():
    """查找可用的浏览器"""
    browsers = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ]

    for browser in browsers:
        if os.path.exists(browser):
            return browser

    # 尝试从 PATH 查找
    for name in ["msedge", "chrome", "chromium"]:
        path = shutil.which(name)
        if path:
            return path

    return None

def convert_svg_to_png(svg_file, png_file=None, width=820, height=1560):
    """
    转换 SVG 到 PNG

    Args:
        svg_file: SVG 文件路径
        png_file: 输出 PNG 文件路径（默认为 SVG 同名.png）
        width: 输出宽度
        height: 输出高度
    """
    svg_file = os.path.abspath(svg_file)

    if not os.path.exists(svg_file):
        print(f"错误: SVG 文件不存在: {svg_file}")
        return False

    if png_file is None:
        png_file = os.path.splitext(svg_file)[0] + ".png"
    else:
        png_file = os.path.abspath(png_file)

    print(f"转换 {os.path.basename(svg_file)} -> {os.path.basename(png_file)}")

    # 查找浏览器
    browser_path = find_browser()
    if not browser_path:
        print("错误: 未找到 Edge 或 Chrome 浏览器")
        return False

    print(f"使用浏览器: {os.path.basename(browser_path)}")

    # 创建临时 HTML 包装 SVG (解决某些浏览器直接打开 SVG 的问题)
    html_content = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <style>
        body {{ margin: 0; padding: 0; overflow: hidden; }}
        img {{ display: block; width: {width}px; height: {height}px; }}
    </style>
</head>
<body>
    <img src="file:///{svg_file.replace(chr(92), '/')}" width="{width}" height="{height}">
</body>
</html>"""

    html_file = os.path.join(os.path.dirname(svg_file), "temp_svg_viewer.html")
    with open(html_file, "w", encoding="utf-8") as f:
        f.write(html_content)

    # 创建临时用户目录 (避免权限问题)
    user_dir = os.path.join(os.path.dirname(svg_file), "temp_browser_profile")

    try:
        # 调用浏览器截图
        cmd = [
            browser_path,
            "--headless",
            "--disable-gpu",
            "--no-sandbox",
            f"--user-data-dir={user_dir}",
            f"--screenshot={png_file}",
            f"--window-size={width},{height}",
            f"file:///{html_file.replace(chr(92), '/')}"
        ]

        result = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
        time.sleep(1)  # 等待文件写入完成

        # 检查结果
        if os.path.exists(png_file):
            file_size = os.path.getsize(png_file)
            print(f"转换完成！PNG 文件: {png_file} ({file_size / 1024:.1f} KB)")
            return True
        else:
            print("转换失败！")
            if result.stderr:
                print(f"错误信息: {result.stderr}")
            return False

    finally:
        # 清理临时文件
        if os.path.exists(html_file):
            os.remove(html_file)
        if os.path.exists(user_dir):
            shutil.rmtree(user_dir, ignore_errors=True)

def main():
    if len(sys.argv) < 2:
        print("用法: python svg2png.py <SVG文件> [PNG文件] [宽度] [高度]")
        print("示例: python svg2png.py input.svg output.png 820 1560")
        sys.exit(1)

    svg_file = sys.argv[1]
    png_file = sys.argv[2] if len(sys.argv) > 2 else None
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 820
    height = int(sys.argv[4]) if len(sys.argv) > 4 else 1560

    success = convert_svg_to_png(svg_file, png_file, width, height)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
