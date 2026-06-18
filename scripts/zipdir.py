#!/usr/bin/env python
"""把目录内容打包成 zip (不含顶层目录), 供 build_module.sh 在无 zip 命令时兜底。
用法: python zipdir.py <src_dir> <out_zip>
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) != 3:
        print("用法: python zipdir.py <src_dir> <out_zip>")
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(src):
            for name in files:
                full = os.path.join(root, name)
                arc = os.path.relpath(full, src).replace(os.sep, "/")
                zf.write(full, arc)
    print("zip 写入:", out)


if __name__ == "__main__":
    main()
