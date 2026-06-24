# WPM Primus (KD360X) Firmware Analysis Report

**File:** `KD360X_OTA_1.1.54_DE_20260623.bin` (3,828,608 bytes / 3.65 MB)
**Analysis date:** 2026-06-24

---

## 1. Header & Identity (from esptool `image_info`)

| Field | Value |
|---|---|
| Chip | **ESP32-S3** (Chip ID 9) |
| Magic | `0xe9` (valid ESP-IDF app image) |
| Entry point | `0x403777d8` |
| ESP-IDF | v4.4.6-dirty |
| Build project name | `arduino-lib-builder` (built via Arduino-ESP32 core) |
| Compiled | Oct 4 2023, 16:40:08 |
| App version | `esp-idf: v4.4.6 3572900934` |
| Flash | 16 MB, 80 MHz, DIO mode |
| Segments | 5 |
| Secure version | 0 (no secure boot enforced) |
| Checksum / Validation hash | **valid** (integrity OK) |

The firmware boots directly — no secure-boot signature is required by the image itself.

## 2. Memory Map (5 segments)

| # | Type | Load addr | Size | Role |
|---|---|---|---|---|
| 0 | DROM | `0x3c110020` | 2.65 MB | **Read-only data** — strings, UI assets, fonts. Richest source of info. |
| 1 | DRAM | `0x3fc96990` | 24 KB | Initialized data (globals) |
| 2 | IRAM | `0x40374000` | 10 KB | Fast instruction RAM |
| 3 | IROM | `0x42000020` | 1.08 MB | **Main application code** (Xtensa LX7 instructions) |
| 4 | IRAM | `0x40376988` | 64 KB | Fast IRAM routines / WiFi BT coex |

Extracted to `analysis/extracted/segN_*.bin`. Per-segment ASCII strings in `analysis/strings_seg*.txt`.

## 3. Software Stack

- **Arduino-ESP32** core (built with `arduino-lib-builder`) on top of ESP-IDF v4.4.6.
- **LVGL** — UI framework (47 string refs). Screen definitions follow the SquareLine Studio naming convention (`ui_<Screen>.cpp`).
- **mbedTLS** — TLS stack with **x509 certificate bundle** (embedded CA bundle, ~multiple roots).
- **ESP32 WiFi + WiFi provisioning** (SoftAP/scan based, "Scan QR code to access OTA panel").
- **NVS** (Non-Volatile Storage) for persistent settings.
- **NeoPixel** support (status LED driver present).
- 42 distinct C++ source files referenced, organised under `src/`: `api/`, `core/`, `databus/`, `draw/`, `hal/`, `netif/`, `ui/`, `widgets/`, etc.

## 4. Device Identity & Regionalisation

- **Model:** `KD360X` (WPM "Primus")
- Supports ~12 regions, each with its own firmware variant / version track:
  `REGION_DE, REGION_EU, REGION_US, REGION_TW, REGION_HK, REGION_KR, REGION_RU, REGION_FR, REGION_SW, REGION_3C, REGION_FW` …
  → This file is the **DE (Germany)** variant (filename + German UI strings).
- Persistent identity keys: `ModelName`, `UnitName`, `AltName`, `GlobalRegion`, `SW_VersionMajor/Minor/Patch`.
- UI is **bilingual** — every user-facing string has an English and a German variant ("Falsche SSID", "Firmware-Update", "OTA-Modus").

## 5. Networking & OTA

**Two OTA firmware endpoints (both Microsoft Azure):**
1. `https://wpmfirmwareprod.blob.core.windows.net/firmware/` — Azure Blob Storage (origin)
2. `https://wpm-firmware-updates-g9hfehacbzaphxfj.a01.azurefd.net/firmware/` — Azure Front Door CDN

**OTA flow** (`ui_OTAUpdater.cpp`, `parseAndCheckFirmware`, `runOTA`, `OTA_Routine`):
1. Device fetches a per-region firmware list from Azure.
2. Compares server version vs local (`"Current firmware is up to date for this region"` / `">>> NEW UPDATE AVAILABLE! <<<"`).
3. Downloads `firmware.bin` over HTTPS.
4. Writes via standard ESP HTTPS OTA (`OTA update started!` → `OTA Progress Current … Final …` → `OTA update finished successfully!`).
5. Region is **downgrade-locked** — once a higher region version is flashed, a lower one cannot be installed (warning strings present).

**Security posture (good):**
- HTTPS/TLS for OTA, CA verified via embedded cert bundle.
- **No hardcoded secrets found** — no API keys, SAS tokens, passwords, or client certificates embedded. The device authenticates the *server*, not vice-versa.
- Note: secure-boot / flash-encryption are **not enforced** (secure version 0), so the flash is readable — which is exactly why this analysis is possible.

There is **no MQTT, no cloud telemetry, no phone-home** — it's a locally-networked device whose only outbound traffic is OTA pulls. (No AWS IoT / Azure IoT Hub client strings present.)

## 6. Espresso Machine — Functional Decomposition

This is the most revealing part. The firmware runs a lever/paddle espresso machine with pressure & flow profiling.

### Control surfaces (input)
- **Paddle / lever** — analog position sensor with calibration. States: `PADDLE ARMED AND READY`, `CheckPaddleAction`, `IsPaddleWithinRange`, `@Paddle value out of range`. Calibrated via `MinPaddle`/`MaxPaddle`/`Calibrate Paddle`.
- **Pressure transducer** — ADC-based, `MinPressureADC`, `PressureSlope`, `Pressure: %.1f => %.1f`. Calibrated via `ui_CalibratePressure` ("9 Bar @ %i").
- **Flow meter** — `Flowrate: %.1f => %.1f`, `BarADC`.
- **NTC thermistors** — `NTC%i: %iC`, `NTC%i: 100C`, `SetTemp`, `ui_SetTemperature`.

### Actuators / control loops
- **Boiler fill management** (`src/HandleFillBoiler.cpp`): fills on boot, monitors sensor stability, has volume limit and max-time limit, and a `"magic bypass"` for skipping fill during service.
- **Pump** — pressure-controlled (`9 Bar` target modes).
- **VPS** = **Volumetric Profiling System** — the headline feature: per-shot pressure/flow curves (`Size of curve profile: %i bytes`, `VPS-%iS`).

### Brew modes
| Mode | Notes |
|---|---|
| `9BarMode` / `9BarPressure` | Classic 9-bar extraction |
| `9BarPreInfusion` | 9-bar with pre-infusion |
| `SemiManual VPS` (w/ and w/o preinfusion) | Semi-manual profiling |
| `Manual VPS` | Full manual pressure control |
| `ManualFlowVPS` | Flow-based profiling |
| `BrewingHold` | Pause-and-hold during brew |
| `PAGE3-BREW` | Brew screen |

### Shot presets
- Single & Double shot volumetric presets: `SAVE DOUBLE SHOT TO %i SEC (%i ML)`, `SAVE DOUBLE SHOT TO %i ML (%i SEC)`.

### Profiles
- User library: `CREATE PROFILE`, `CREATE FLOW PROFILE`, `SwitchProfile`, `InsertHistoryProfile`, `DeleteHistoryProfile`, `ProfileList`, `ViewUserProfile`.
- History of brewed shots stored (`Paddle history list`).

### Service / Engineer menus
- **`ui_GodDoor`** → `"GOTO GOD MODE"` / `"GOTO FACTORY MODE"` / `"GOTO SELECT REGION"` / `"GOTO OTA MODE"` / `"WIPE NVS"` / `"RESET TO FACTORY DEFAULT"`.
- **`ui_GodMode`** — the full service/calibration dashboard.
- **Calibration screens:** `Calibrate Paddle`, `Calibrate Pressure`, `TunningCups` (cup volumetric tuning), `Drainage`, `WaterEmptyFill`.
- **`PaddleMonitor`** — live paddle/pressure/flow telemetry.

## 7. Complete UI Screen List (22 screens)

```
ui_AddFlowProfilePage2   ui_AddProfilePage1     ui_AddProfilePage3
ui_BrewingHold           ui_CalibratePressure   ui_Drainage
ui_EditSelection         ui_EnterWifiPassword   ui_GodDoor
ui_GodMode               ui_Keyboard            ui_MainPage
ui_OTAUpdater            ui_PaddleMonitor       ui_ProfileList
ui_ResetMenu             ui_SemiBrewing         ui_SemiManual_VPS_Sequence
ui_SetPinned             ui_TunningCups         ui_ViewUserProfile
ui_WaterEmptyFill
```

## 8. Files Produced

```
analysis/
├── extracted/
│   ├── seg0_DROM_0x3c110020.bin   (2.65 MB — strings/assets)
│   ├── seg1_DRAM_0x3fc96990.bin   (24 KB)
│   ├── seg2_IRAM_0x40374000.bin   (10 KB)
│   ├── seg3_IROM_0x42000020.bin   (1.08 MB — app code)
│   └── seg4_IRAM_0x40376988.bin   (64 KB)
├── strings_seg0_DROM.txt          (6,738 lines)
├── strings_seg1_DRAM.txt
├── strings_seg2_IRAM.txt
├── strings_seg3_IROM.txt          (2,124 lines)
├── strings_seg4_IRAM.txt
└── PHASE1_OVERVIEW.md             (this file)
```

## 9. Summary & Next Steps

The KD360X Primus is an **ESP32-S3 + LVGL touchscreen** espresso machine running Arduino-ESP32/IDF 4.4.6. It is a **local-only device** (no cloud telemetry/MQTT) whose sole outbound connectivity is **region-aware OTA over HTTPS from Azure Blob/Front Door**. Notably, it carries **no embedded secrets** — a clean security design, though **secure boot and flash encryption are off**, leaving the flash fully readable.

Engineering-wise it's a sophisticated **pressure + flow profiling** machine (VPS) with paddle/lever input, NTC temperature control, boiler-fill management, per-user shot libraries, and a hidden **"God Mode" service/calibration menu**.

**Possible follow-ups** (if you want to go deeper):
- Disassemble seg3/seg4 with a proper Xtensa toolchain — no native disassembler is installed here. Options: **Ghidra** (with the ESP32/S3 processor module), **radare2** (`r2 -a xtensa`), or `xtensa-esp32-elf-objdump` from the ESP-IDF toolchain.
- Map LVGL screen object tables in seg0 to reconstruct the actual UI layout/widgets.
- Check the live Azure endpoints to confirm the firmware-list JSON schema and available versions per region.
