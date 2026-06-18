#!/usr/bin/env python
"""Magisk 模块 zip 解包/打包工具 (Windows 无 zip 命令时使用)。

用法:
  python ziptool.py extract <zip> <dir>   解压到目录
  python ziptool.py pack    <dir> <zip>   把目录内容打包 (zip 内不含顶层目录)
"""
import os
import sys
import zipfile


def extract(zip_path, out_dir):
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(out_dir)
    print("解压完成:", out_dir)


def pack(src_dir, out_zip):
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(src_dir):
            for name in files:
                full = os.path.join(root, name)
                arc = os.path.relpath(full, src_dir).replace(os.sep, "/")
                zf.write(full, arc)
    print("打包完成:", out_zip)


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    mode, a, b = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode == "extract":
        extract(a, b)
    elif mode == "pack":
        pack(a, b)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
