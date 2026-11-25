# Comfort & Startup (`hmgapi-comfort`)

A **Material Design 3** cabin comfort & startup automation mod for **Hyundai, Kia, and Genesis Gen5W** widescreen head units running **[HMG-API](https://github.com/Open-HMG/HMG-API)** (`>= 1.0.0`).

Automatically dismisses the infotainment startup confirmation screen across cold boots and QuickBoot wakes, warms your seats and steering wheel across 3 customizable winter temperature tiers, ventilates front seats across 3 summer cooling tiers, and gives you a native two-pane **Material Design 3** dashboard right on your car's `1920×720` display.

> **Looking for the full one-tap US Ioniq 5 bundle?** Comfort & Startup is also included in **[`gen5w-suite`](https://github.com/lemoyneiv/gen5w-suite)**. This repository provides **standalone packages** for the **NEMESIS Package Store**, NEMESIS USB, and the HMG-API app on any Gen5W vehicle.

---

## Features

- **Instant Startup Confirmation Dismissal (`WelcomeDaemon`)**
  - Lightweight Android-stage daemon (`start: "android"`) that dismisses the infotainment consent notice right after `sys.boot_completed=1` on cold boot and within `200 ms` on QuickBoot suspend-to-RAM resume—only while safely in Park (`P`), while deferring background climate service startup by `12 s` on cold boot so Wireless CarPlay / Android Auto connects with zero IPC contention and staying dormant during dark/silent parked wakes.
  - Configurable **Dismiss Delay (`0–2500 ms`)** if you prefer a brief pause after wake.
- **3-Tier Cold-Weather Seat & Steering Wheel Warming**
  - Automatic **Metric (`°C`) & Imperial (`°F`) Vehicle Unit Support**: detects your car's active temperature unit setting (`°C` or `°F`) automatically, displays live outside temperature and seasonal spectrum ranges in your preferred unit, and steps thresholds in whole `1 °C` or `1 °F` increments (defaults: `≤ -4 °C` / `25 °F` → L3, `≤ 3 °C` / `38 °F` → L2, `≤ 10 °C` / `50 °F` → L1, plus independent **Steering Wheel Heat** at `≤ 7 °C` / `45 °F`).
  - Activates both on **EV Ready** drive starts and during **plugged-in / charging / Utility Mode** cabin sessions when you enter **Ignition ON** (`Start` without brake while tethered, shown as `Charge · ON`)—while distinguishing unpowered **ACC** standby (`Charge · ACC`) and staying completely silent during unoccupied overnight charging or dark-screen remote Bluelink wakes.
  - Includes a standalone UID-1000 `DATC` / `BCM` Binder push bridge so live outside temperature (`°C` and `°F`) and steering wheel heat recover immediately on QuickBoot wakes while plugged in and charging, ignores raw `-40` pre-CAN wake sentinels, verifies seat/wheel state closed-loop against BCM/VR, and **never overrides** active Bluelink or manual seat/wheel settings.
- **3-Tier Warm-Weather Front Seat Ventilation**
  - Automatic summer seat cooling thresholds (`1 °C` or `1 °F` increments) for **Level 3**, **Level 2**, and **Level 1** front ventilation (defaults: `≥ 35 °C` / `95 °F` → L3, `≥ 29 °C` / `85 °F` → L2, `≥ 24 °C` / `75 °F` → L1) on vehicles equipped with ventilated seats—automatically hidden on trims without ventilated seats.
- **Automatic Front-Passenger Occupancy Detection, Trim-Aware Hardware Detection & Rear Seat Policies**
  - **Automatic Trim Capability Detection**: reads live Gen5W `BCM` / `USM` seat option codes (`*SeatHeatOption`) and silently hides ventilated front-seat or heated rear-seat tiles, toggles, policies, and summer threshold cards when not equipped on the vehicle.
  - **Front Passenger Policy** (`Off`, `-1 Step` gentler than driver, or `Match` driver) for both heating and cooling.
  - **Only When Passenger Detected** *(enabled by default)*: reads live Gen5W seat-occupancy classification & buckle sensors (`HSCAN.getOccupancy`, `SYS.getClassStatus`, `HSCAN.getAsstStBltSwSta`) so solo drives never heat or cool an empty passenger seat—and **automatically turns on passenger comfort mid-session** if a passenger sits down or buckles up after startup.
  - **Rear Heated Seats Policy** (`Off`, `-1 Step`, or `Match` up to 2 rear levels) on vehicles equipped with rear seat heaters.
- **Material Design 3 Widescreen Dashboard (`1920×720`)**
  - Adaptive **Day / Night** M3 tonal surfaces (`UiModeManager`), animated stadium switches, segmented controls, live cabin seat preview tiles, a seasonal comfort spectrum bar with live outside temperature pin, one-tap **Run Climate Check** test button, **Star (★) Key** binding support, and stock-gradient Gen5W launcher tiles.
  - Zero `/system` file modifications and zero firmware-specific hook points—removing or disabling the mod leaves the head unit 100% stock.

---

## Requirements

- A **Hyundai, Kia, or Genesis** vehicle with a **Gen5W** navigation head unit.
- **[HMG-API](https://github.com/Open-HMG/HMG-API)** `1.0.0` or newer installed ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).
- **NEMESIS** (`>= 1.16`) if installing from the **NEMESIS Package Store** or a NEMESIS USB stick.

---

## Installation

Install **[HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)** first, then install Comfort & Startup using whichever method you prefer:

| Release Asset | Use For |
|---|---|
| **`hmgapi-comfort-v<version>-nemesis-package.zip`** | **NEMESIS Package Store** (`hmgapi.hmgapi-comfort`) or `nemesis install` *(do not unzip onto a flash drive)* |
| **`hmgapi-comfort-v<version>-nemesis-usb.zip`** | Installing via **NEMESIS** from a FAT32 / exFAT USB flash drive |
| **`hmgapi-comfort-v<version>-hmgapi-usb.zip`** | Installing via the **HMG-API** app (**Updates** tab) from a FAT32 / exFAT USB flash drive |
| **`hmgapi-comfort-v<version>.zip`** | Signed HMG-API mod package on its own (`hmgapid engine install`) |

### Option 1 — NEMESIS Package Store
Search for **Comfort & Startup** (`hmgapi.hmgapi-comfort`) in the **NEMESIS Package Store** and tap **Install**. Tapping **Launch** in NEMESIS Manager opens the **Comfort** Material 3 dashboard directly.

### Option 2 — With NEMESIS (USB Flash Drive)
1. Unzip **`hmgapi-comfort-v<version>-nemesis-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so `NEMESIS.data` and `packages/` are at the top level (keep only one NEMESIS USB package on the stick).
2. With Android booted on the head unit, plug the USB drive into the car's data USB port and accept the **Comfort & Startup** install prompt.
3. Remove the USB drive once complete. *(Note: automatically refuses to install alongside legacy `coldstart.heat` or `local.hkmc.factory82` packages so two mods never fight over seat heat or startup notice dismissal—uninstall those first if prompted.)*

### Option 3 — With the HMG-API App (USB Flash Drive)
1. Unzip **`hmgapi-comfort-v<version>-hmgapi-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so the drive contains `HMGAPI/updates/hmgapi-comfort-v<version>.zip`.
2. Plug the drive into the car, open the **HMG-API** app → **Updates**, install **Comfort & Startup**, and tap **Apply changes**.

---

## Required First-Run Permissions in HMG-API

HMG-API protects vehicle climate and startup commands behind owner switches. After installing **Comfort & Startup**, open the **HMG-API** app → **Settings / Permissions** once and turn on:

1. **Dismiss the startup notice** (global owner switch + **Comfort & Startup** `startup_notice`)
2. **Seat heat** (`seat_heat` for **Comfort & Startup**)
3. **Seat ventilation** (`seat_vent` for **Comfort & Startup**, if your car has ventilated seats)
4. **Steering wheel heat** (`wheel_heat` for **Comfort & Startup**, if your car has a heated wheel)

Then open **Comfort** from the head unit's **All Apps** screen (or **NEMESIS Manager → Launch**, or bind **Comfort & Startup** to your steering-wheel/dash **★** button) and tap **Run Climate Check** to test immediately.

---

## Compatibility

Comfort & Startup uses HMG-API's cross-vehicle `vehicle` service (`outside_temp`, `outside_temp_f`, `hv.ready`, `gear.park`, `charging`, `seat_heat.*`, `seat_vent.*`, `seat_state.*`, `wheel_heat`, `startup_notice`) and requires **no per-firmware bytecode hooks**.

| Vehicle | Firmware Tested | HMG-API |
|---|---|---|
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`, `NEEV23SE`, US) | `260507`, `260903` | `1.1.0-rc.3+` |

Tried it on another Hyundai, Kia, or Genesis Gen5W vehicle? Open a [Car Compatibility Report](../../issues/new?template=car-report.yml) to add your model to the table!

---

## Building from Source

```sh
./build.sh
```

`build.sh` automatically downloads and SHA-256-verifies the official **[Open-HMG/HMG-API](https://github.com/Open-HMG/HMG-API)** Mod SDK pinned in [`hmgapi-sdk.json`](hmgapi-sdk.json), compiles `Comfort.apk` targeting Android 4.4.2 (`API 19`), runs all host policy unit tests (`hmgapi-comfort/tools/unit/test.sh`), package tests (`hmgapi-comfort/test.sh`), and head-unit `mksh` script checks (`check-scripts.sh`), and writes signed HMG-API + NEMESIS packages into `dist/`.

Official releases are signed with author key **`28ad71e0-07c0-8e61-8bf3-380cc71f9280`** (`lemoyneiv`).

---

## License & Disclaimer

Licensed under **[GNU AGPL-3.0](LICENSE)**. Built on **[Open-HMG/HMG-API](https://github.com/Open-HMG/HMG-API)**.

Not affiliated with or endorsed by Hyundai, Kia, or Genesis. Modifying your vehicle's infotainment head unit is at your own risk.
