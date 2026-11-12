#!/bin/sh
# Сборка: нужен llvm-mingw (https://github.com/mstorsjo/llvm-mingw)
set -e
cd "$(dirname "$0")"
CXX=${CXX:-x86_64-w64-mingw32-clang++}
RC=${RC:-x86_64-w64-mingw32-windres}
FLAGS="-O2 -std=c++17 -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 -DNTDDI_VERSION=0x0A00000C -static -municode"

# версия из desktop/build.gradle.kts — в ресурсы файлов, чтобы установщик их обновлял
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' ../desktop/build.gradle.kts)
TMP=../build/driver
mkdir -p ../dist "$TMP"
printf '#define VER %s,0\n#define VER_STR "%s"\n' "$(echo "$VERSION" | tr . ,)" "$VERSION" > "$TMP/ver.h"
$RC -I "$TMP" -DNAME='\"Oko Camera\"' version.rc -O coff -o "$TMP/dll.res"
$RC -I "$TMP" -DNAME='\"Oko Camera control\"' version.rc -O coff -o "$TMP/ctl.res"

$CXX $FLAGS -shared -o ../dist/Camera2PCCam.dll vcam.cpp vcam.def "$TMP/dll.res" \
    -lmfplat -lmfuuid -lmf -lole32 -luuid -ladvapi32 -lpropsys -lwinmm
$CXX $FLAGS -o ../dist/vcamctl.exe vcamctl.cpp "$TMP/ctl.res" \
    -lmfplat -lmfuuid -lmf -lmfreadwrite -lole32 -luuid
echo OK
