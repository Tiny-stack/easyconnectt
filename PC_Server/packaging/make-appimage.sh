#!/usr/bin/env bash
#
# Build a distributable .AppImage for PC_Server.
#
# jpackage CANNOT emit AppImage — its Linux types are only app-image (a folder),
# deb and rpm. So we do it in two steps:
#   1. jpackage --type app-image  -> a self-contained folder (bundled JRE+launcher)
#   2. wrap that folder in an AppDir and run appimagetool -> one .AppImage file
#
# Build this on the OLDEST glibc you want to support (CI pins ubuntu-22.04): the
# bundled JRE/launcher link against the build machine's glibc, so the AppImage
# only runs on targets whose glibc is >= the build host's. musl distros (Alpine)
# are not supported. x86_64 only.
set -euo pipefail

cd "$(dirname "$0")/.."          # -> PC_Server

APPNAME="$(grep '^appName=' gradle.properties | cut -d= -f2)"      # PCServer
# Version = the repo-root VERSION file (shared with the Android app).
VERSION="$(tr -d '[:space:]' < ../VERSION)"                        # e.g. 1.0.0
ARCH="${ARCH:-x86_64}"

IMG="build/jpackage/${APPNAME}"          # jpackage app-image folder
APPDIR="build/appimage/${APPNAME}.AppDir"
OUT="build/appimage/${APPNAME}-${VERSION}-${ARCH}.AppImage"

# 1. Produce the portable app-image (bundled JRE + native launcher).
echo "==> jpackage app-image (version ${VERSION})"
./gradlew jpackageImage --no-daemon -PappVersion="${VERSION}"
[ -x "${IMG}/bin/${APPNAME}" ] || { echo "ERROR: launcher ${IMG}/bin/${APPNAME} not found"; exit 1; }

# 2. Lay out the AppDir: the whole app-image at the root, plus the three files
#    appimagetool requires — AppRun, one .desktop, and a matching icon.
echo "==> assembling AppDir"
rm -rf build/appimage
mkdir -p "${APPDIR}"
cp -a "${IMG}/." "${APPDIR}/"

cat > "${APPDIR}/AppRun" <<EOF
#!/bin/bash
# Resolve our own location and launch the bundled jpackage launcher, forwarding
# any args (e.g. --nogui, --port). Everything is relative, so the AppImage is
# fully self-contained.
HERE="\$(dirname "\$(readlink -f "\${0}")")"
exec "\${HERE}/bin/${APPNAME}" "\$@"
EOF
chmod +x "${APPDIR}/AppRun"

cat > "${APPDIR}/pcserver.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=PC Remote Server
Comment=Control this PC from your phone over the LAN
Exec=${APPNAME}
Icon=pcserver
Categories=Utility;Network;
Terminal=false
EOF

cp icon.png "${APPDIR}/pcserver.png"     # must match Icon= above

# 3. Fetch appimagetool (an AppImage itself) and build. --appimage-extract-and-run
#    avoids needing FUSE/libfuse2 on the build host (GitHub runners lack it).
echo "==> appimagetool"
TOOL="build/appimage/appimagetool.AppImage"
if [ ! -x "${TOOL}" ]; then
    curl -fsSL -o "${TOOL}" \
        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-${ARCH}.AppImage"
    chmod +x "${TOOL}"
fi

ARCH="${ARCH}" "${TOOL}" --appimage-extract-and-run "${APPDIR}" "${OUT}"
echo ""
echo "✔ AppImage: ${OUT}"
