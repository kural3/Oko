#!/bin/sh
# Builds dist/Oko-<version>.msi from the app folder and the camera DLL. Run from Git Bash after
#   ./gradlew :desktop:createDistributable   and   sh driver/build.sh
# WiX 3.11 is taken from $WIX, or downloaded into build/wix311 (as the Compose plugin does).
set -e
cd "$(dirname "$0")/.."

VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' desktop/build.gradle.kts)
APP=desktop/build/compose/binaries/main/app/Oko
OUT=build/installer
WIX=${WIX:-build/wix311}

[ -f "$APP/Oko.exe" ] || { echo "No $APP: run ./gradlew :desktop:createDistributable"; exit 1; }
[ -f dist/Camera2PCCam.dll ] || { echo "No dist/Camera2PCCam.dll: run sh driver/build.sh"; exit 1; }

if [ ! -f "$WIX/candle.exe" ]; then
    mkdir -p "$WIX"
    curl -sSL -o build/wix311.zip https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip
    unzip -q -o build/wix311.zip -d "$WIX"
    rm build/wix311.zip
fi

rm -rf "$OUT" && mkdir -p "$OUT"
java installer/Art.java "$OUT"

"$WIX/heat.exe" dir "$APP" -nologo -cg AppFiles -dr INSTALLFOLDER -srd -gg -sfrag -sreg -ke \
    -var var.AppDir -out "$OUT/AppFiles.wxs"
"$WIX/candle.exe" -nologo -arch x64 -ext WixUtilExtension \
    -dVersion="$VERSION" -dAppDir="$APP" -dDist=dist -dIcon=desktop/icons/oko.ico -dArt="$OUT" \
    installer/Oko.wxs "$OUT/AppFiles.wxs" -out "$OUT/"
"$WIX/light.exe" -nologo -ext WixUIExtension -ext WixUtilExtension -cultures:ru-ru -spdb -sice:ICE38 -sice:ICE43 -sice:ICE57 -sice:ICE60 -sice:ICE61 \
    "$OUT/Oko.wixobj" "$OUT/AppFiles.wixobj" -out "dist/Oko-$VERSION.msi"
echo "dist/Oko-$VERSION.msi"
