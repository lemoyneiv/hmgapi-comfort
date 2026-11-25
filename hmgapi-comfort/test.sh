#!/bin/sh
# Off-car engine & package structure test for hmgapi-comfort using the HMG-API Mod SDK.
set -e
M=$(cd "$(dirname "$0")" && pwd)
B=${HMGAPI_MOD_BUILD:?set HMGAPI_MOD_BUILD to the built mod (the out dir of bin/build-mod.sh)}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"

sh "$M/tools/unit/test.sh"

mkdir -p "$R/system" "$R/data/hmgapi/settings"
printf 'ro.board.platform=tcc803x\n' > "$R/props.txt"

check "built: Comfort.apk exists" yes "$(test -f "$B/app/Comfort.apk" && echo yes || echo no)"
DEX_STRINGS=$(unzip -p "$B/app/Comfort.apk" classes.dex | strings)
has "apk contains ComfortService" "Ldev/lemoyne/comfort/ComfortService;" "$DEX_STRINGS"
has "apk contains ComfortEngine" "Ldev/lemoyne/comfort/ComfortEngine;" "$DEX_STRINGS"
has "apk contains ComfortPolicy" "Ldev/lemoyne/comfort/ComfortPolicy;" "$DEX_STRINGS"
has "apk contains WelcomeDaemon" "Ldev/lemoyne/comfort/WelcomeDaemon;" "$DEX_STRINGS"
has "apk contains MainActivity" "Ldev/lemoyne/comfort/MainActivity;" "$DEX_STRINGS"
has "apk contains ComfortTheme" "Ldev/lemoyne/comfort/ComfortTheme;" "$DEX_STRINGS"
has "apk contains ComfortIcons" "Ldev/lemoyne/comfort/ComfortIcons;" "$DEX_STRINGS"
has "apk contains ComfortWidgets" "Ldev/lemoyne/comfort/ComfortWidgets;" "$DEX_STRINGS"
has "apk contains BootReceiver" "Ldev/lemoyne/comfort/BootReceiver;" "$DEX_STRINGS"
has "apk contains OccupantSignalProbe" "Ldev/lemoyne/comfort/OccupantSignalProbe;" "$DEX_STRINGS"
has "apk contains CabinPushBridge" "Ldev/lemoyne/comfort/CabinPushBridge;" "$DEX_STRINGS"
check "apk omits removed ComfortUsbExporter" no "$(echo "$DEX_STRINGS" | grep -q 'Ldev/lemoyne/comfort/ComfortUsbExporter;' && echo yes || echo no)"
has "apk bundles HmgApi client" "Lhmgapi/client/HmgApi;" "$DEX_STRINGS"

for ic in icon.png icon_night.png icon_disabled.png icon_disabled_night.png; do
    check "launcher tile $ic exists" yes "$(test -f "$M/icons/$ic" && echo yes || echo no)"
done

d=$(HMGAPI_NEMESIS_CONFLICTS=/dev/null python3 "$HMGAPI_SDK/bin/nemesis-meta.py" depends hmgapi.hmgapi-comfort 'hmgapi@>=1.0' --mod "$M/mod.json")
check "NEMESIS conflicts block coldstart.heat and local.hkmc.factory82" "hmgapi@>=1.0,!coldstart.heat,!local.hkmc.factory82" "$d"
check "hmgapi-comfort attributed solely to lemoyneiv" "lemoyneiv" \
    "$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["authors"]))' "$M/mod.json")"

install_mod "$B"
cp -r "$M/icons" "$R/data/hmgapi/mods/hmgapi-comfort/"

eng plan
check "engine plan succeeds" 0 "$rc"
has "plan lists Comfort.apk" "dev.lemoyne.comfort (app/Comfort.apk)" "$out"
has "plan lists fast-path welcome daemon as system at android" "service  hmgapi-comfort/welcome as system, at android, restarted if it stops" "$out"
check "plan omits removed starter shell service" no "$(echo "$out" | grep -q 'hmgapi-comfort/starter' && echo yes || echo no)"
check "plan has zero problems" no "$(echo "$out" | grep -q '^problem ' && echo yes || echo no)"

eng apply
check "engine apply succeeds" 0 "$rc"
has "mod published as on" "1 of 1 mods on" "$out"
has "mod in public state.json" '"hmgapi-comfort"' "$(cat "$R/data/hmgapi/public/state.json")"
check "no ComfortProbe USB folder registered in usb.txt" no "$(grep -q 'ComfortProbe' "$R/data/hmgapi/state/usb.txt" 2>/dev/null && echo yes || echo no)"
has "welcome daemon registered in services.txt at android" "hmgapi-comfort welcome system android yes jar app/Comfort.apk dev.lemoyne.comfort.WelcomeDaemon" "$(cat "$R/data/hmgapi/state/services.txt")"
check "no starter script registered in services.txt" no "$(grep -q 'hmgapi-comfort starter' "$R/data/hmgapi/state/services.txt" 2>/dev/null && echo yes || echo no)"
for cmd in startup_notice seat_heat seat_vent wheel_heat; do
    has "$cmd in commands.txt" "hmgapi-comfort $cmd" "$(cat "$R/data/hmgapi/state/commands.txt")"
done
for sig in hv.ready gear.park charging outside_temp_f outside_temp wheel_heat seat_state.driver; do
    has "$sig in vehicle.txt" "hmgapi-comfort $sig" "$(cat "$R/data/hmgapi/state/vehicle.txt")"
done

printf '{"heat_high_f":28,"passenger_policy":"match","passenger_auto_detect":false,"vent_enabled":false}\n' > "$R/data/hmgapi/settings/hmgapi-comfort.json"
eng apply
check "setting override applies cleanly" 0 "$rc"
has "public state reflects passenger_policy match" '"match"' "$(cat "$R/data/hmgapi/public/state.json")"
has "public state exposes passenger_auto_detect setting" '"passenger_auto_detect"' "$(cat "$R/data/hmgapi/public/state.json")"

eng remove hmgapi-comfort
check "engine remove succeeds" 0 "$rc"
eng apply
check "apply after remove succeeds" 0 "$rc"
exit "$fail"
