#!/bin/sh
# Build, test, and sign installable HMG-API and NEMESIS packages into dist/:
#   dist/hmgapi-comfort-v<ver>.zip                 Signed HMG-API mod archive (for HMG-API catalogue / CLI)
#   dist/hmgapi-comfort-v<ver>-hmgapi-usb.zip      FAT32 USB stick for HMG-API -> Updates
#   dist/hmgapi-comfort-v<ver>-nemesis-package.zip NEMESIS Package Store archive (portal.xwtk.cloud/nemesis)
#   dist/hmgapi-comfort-v<ver>-nemesis-usb.zip     FAT32 USB stick for NEMESIS auto-prompt install
#   dist/SHA256SUMS
#
# Signing keys (kept private outside the repo; reuse the same keys for updates):
#   HMGAPI_KEY      mod author ECDSA key (default: ~/.hmgapi/author.key, or ~/.gemini/hmgapi/author.key)
#   HMGAPI_APP_KEY  APK PKCS#12 keystore, password "example" (default alongside author.key)
#   HMGAPI_SDK      unpacked HMG-API Mod SDK (auto-downloaded and sha256-verified from hmgapi-sdk.json)
#   ANDROID_HOME    Android SDK with platforms/android-19 and build-tools
set -e
cd "$(dirname "$0")"
MOD=hmgapi-comfort
VER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MOD/mod.json")

# Locate Android SDK (Linux or macOS Homebrew commandlinetools)
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/Android" ]; then ANDROID_HOME="$HOME/Android"
    elif [ -d /opt/homebrew/share/android-commandlinetools ]; then ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"
    fi
fi
export ANDROID_HOME

# Locate or fetch official HMG-API Mod SDK pinned in hmgapi-sdk.json
if [ -z "${HMGAPI_SDK:-}" ]; then
    SDK_VER=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["version"])')
    SDK_SHA=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["sha256"])')
    SDK_CACHE=".cache/sdk/hmgapi-mod-sdk-${SDK_VER}"
    if [ ! -f "$SDK_CACHE/VERSION" ]; then
        mkdir -p .cache/sdk
        SDK_ZIP=".cache/sdk/hmgapi-mod-sdk-${SDK_VER}.zip"
        if [ ! -f "$SDK_ZIP" ]; then
            if [ -f "../gen5w-suite/.cache/releases/sdk/hmgapi-mod-sdk-${SDK_VER}.zip" ]; then
                cp "../gen5w-suite/.cache/releases/sdk/hmgapi-mod-sdk-${SDK_VER}.zip" "$SDK_ZIP"
            elif command -v curl >/dev/null 2>&1 && curl -fsSL -o "$SDK_ZIP" "https://github.com/Open-HMG/HMG-API/releases/download/v${SDK_VER}/hmgapi-mod-sdk-${SDK_VER}.zip" 2>/dev/null; then
                :
            elif command -v gh >/dev/null 2>&1; then
                gh release download "v${SDK_VER}" -R Open-HMG/HMG-API --pattern "hmgapi-mod-sdk-${SDK_VER}.zip" --dir .cache/sdk --clobber
            else
                echo "build.sh: set HMGAPI_SDK or install curl/gh to download hmgapi-mod-sdk-${SDK_VER}.zip" >&2
                exit 1
            fi
        fi
        echo "${SDK_SHA}  ${SDK_ZIP}" | sha256sum -c - >/dev/null
        rm -rf "$SDK_CACHE" && mkdir -p "$SDK_CACHE"
        unzip -q "$SDK_ZIP" -d "$SDK_CACHE"
    fi
    HMGAPI_SDK=$(cd "$SDK_CACHE" && pwd)
fi
export HMGAPI_SDK

# Locate or create persistent private signing keys
if [ -n "${HMGAPI_KEY:-}" ]; then
    KEY_DIR=$(dirname "$HMGAPI_KEY")
elif [ -f "$HOME/.hmgapi/author.key" ]; then
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
elif [ -d "$HOME/.gemini" ]; then
    KEY_DIR="$HOME/.gemini/hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
else
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
fi

umask 077
mkdir -p "$KEY_DIR"
if [ ! -f "$HMGAPI_KEY" ]; then
    java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing keygen "$HMGAPI_KEY"
fi
export HMGAPI_APP_KEY=${HMGAPI_APP_KEY:-$KEY_DIR/hmgapi-comfort-app.p12}
if [ ! -f "$HMGAPI_APP_KEY" ]; then
    keytool -genkeypair -keystore "$HMGAPI_APP_KEY" -storetype pkcs12 \
        -storepass example -keypass example -alias app \
        -keyalg RSA -keysize 2048 -validity 15000 \
        -dname "CN=Comfort and Startup Gen5W" >/dev/null 2>&1
fi
umask 022

echo "== 1. Build APK & stage mod =="
rm -rf out/build out/work out/packages out/dist dist
python3 "$HMGAPI_SDK/bin/check-points.py" "$MOD" --repo Open-HMG/HMG-API
sh "$HMGAPI_SDK/bin/build-mod.sh" "$MOD" "out/build/$MOD" "out/work/$MOD"

echo "== 2. Run tests & head-unit script check =="
sh "$MOD/tools/unit/test.sh"
HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$MOD/test.sh"
sh "$HMGAPI_SDK/bin/check-scripts.sh" "$MOD"

echo "== 3. Sign HMG-API & NEMESIS installable packages =="
PKG_OUT=$(HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$HMGAPI_SDK/bin/mkmodpkg.sh" "$MOD" "$HMGAPI_KEY")
echo "$PKG_OUT"
SIGNED_ZIP=$(echo "$PKG_OUT" | tail -1)
sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$SIGNED_ZIP"

mkdir -p dist
HMGAPI_ZIP="dist/${MOD}-v${VER}.zip"
HMGAPI_USB="dist/${MOD}-v${VER}-hmgapi-usb.zip"
NEM_PKG="dist/${MOD}-v${VER}-nemesis-package.zip"
NEM_USB="dist/${MOD}-v${VER}-nemesis-usb.zip"

cp "$SIGNED_ZIP" "$HMGAPI_ZIP"
cp "out/dist/${MOD}-${VER}-nemesis-package.zip" "$NEM_PKG"
cp "out/dist/${MOD}-${VER}-nemesis.zip" "$NEM_USB"
UUID=$(java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing uuid "${HMGAPI_KEY}.pub")

# Replace bin/open.sh in NEMESIS package so NEMESIS Manager -> Launch opens Comfort directly,
# preserve settings across author-key/APK cert rotations, and wrap the HMG-API USB stick zip.
python3 - "$MOD" "$VER" "$HMGAPI_ZIP" "$HMGAPI_USB" "$NEM_PKG" "$NEM_USB" "$UUID" <<'PY'
import os, sys, zipfile

mod, ver, hmg_zip, hmg_usb, nem_pkg, nem_usb, uuid = sys.argv[1:8]

def read_from_zip(path, member):
    with zipfile.ZipFile(path, "r") as z:
        return z.read(member)

def swap_in_zip(path, member, new_bytes):
    with zipfile.ZipFile(path, "r") as z:
        items = [(i, z.read(i)) for i in z.infolist()]
    with zipfile.ZipFile(path, "w") as z:
        for info, data in items:
            z.writestr(info, new_bytes if info.filename == member else data)

post_sh = read_from_zip(nem_pkg, "scripts/postinstall.sh").decode("utf-8")
old_block = """new=yes
[ -d "${ROOT:-}/data/hmgapi/mods/$ID" ] && new=no
if ! "$H" engine install "$PWD/mod/package.zip" $any --from-nemesis; then"""
new_block = f"""was_off=0
if [ -d "${{ROOT:-}}/data/hmgapi/mods/$ID" ] && ! grep -q "{uuid}" "${{ROOT:-}}/data/hmgapi/mods/$ID/META-HMGAPI/manifest.json" 2>/dev/null; then
    [ -f "${{ROOT:-}}/data/hmgapi/mods/$ID/.disabled" ] && was_off=1
    "$H" engine remove "$ID" --from-nemesis >/dev/null 2>&1
    if [ "$(getprop sys.boot_completed)" = 1 ]; then
        pm uninstall dev.lemoyne.comfort >/dev/null 2>&1
    fi
fi
new=yes
[ -d "${{ROOT:-}}/data/hmgapi/mods/$ID" ] && new=no
if ! "$H" engine install "$PWD/mod/package.zip" $any --from-nemesis; then"""
assert old_block in post_sh, "unexpected postinstall.sh template"
post_sh = post_sh.replace(old_block, new_block, 1)
post_sh = post_sh.replace(
    'if ! r=$("$H" engine apply 2>&1); then',
    '[ "$was_off" = 1 ] && : > "${ROOT:-}/data/hmgapi/mods/$ID/.disabled"\nif ! r=$("$H" engine apply 2>&1); then',
    1,
)
os.makedirs("out/work", exist_ok=True)
with open("out/work/postinstall-check.sh", "wb") as f:
    f.write(post_sh.encode("utf-8"))
swap_in_zip(nem_pkg, "scripts/postinstall.sh", post_sh.encode("utf-8"))

open_sh = os.path.join(mod, "open.sh")
if os.path.isfile(open_sh):
    swap_in_zip(nem_pkg, "bin/open.sh", open(open_sh, "rb").read())
swap_in_zip(nem_usb, f"packages/hmgapi.{mod}.zip", open(nem_pkg, "rb").read())

with zipfile.ZipFile(hmg_usb, "w", zipfile.ZIP_STORED) as z:
    zi = zipfile.ZipInfo(f"HMGAPI/updates/{mod}-v{ver}.zip", (2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16
    z.writestr(zi, open(hmg_zip, "rb").read())
PY
sh "$HMGAPI_SDK/bin/check-scripts.sh" out/work/postinstall-check.sh

(cd dist && sha256sum *.zip > SHA256SUMS)
echo "== Built installable packages (author key UUID: $UUID) =="
cat dist/SHA256SUMS
