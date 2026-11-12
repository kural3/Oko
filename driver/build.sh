#!/bin/sh
# Сборка: нужен llvm-mingw (https://github.com/mstorsjo/llvm-mingw)
set -e
cd "$(dirname "$0")"
CXX=${CXX:-x86_64-w64-mingw32-clang++}
mkdir -p ../dist
$CXX -O2 -std=c++17 -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 -DNTDDI_VERSION=0x0A00000C -shared -static -municode -o ../dist/Camera2PCCam.dll vcam.cpp vcam.def \
    -lmfplat -lmfuuid -lmf -lole32 -luuid -ladvapi32 -lpropsys -lwinmm
$CXX -O2 -std=c++17 -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 -DNTDDI_VERSION=0x0A00000C -static -municode -o ../dist/vcamctl.exe vcamctl.cpp \
    -lmfplat -lmfuuid -lmf -lmfreadwrite -lole32 -luuid
echo OK
