# WPM Primus (KD360X) — Firmware Internals

> Reverse-engineering documentation for the **open-source alternative firmware** project.
>
> Source: `KD360X_OTA_1.1.54_DE_20260623.bin` (DE region, v1.1.54, built 2023-10-04)
> Analysis method: image parse (esptool) + segment extraction + static string/symbol analysis + Capstone-6 Xtensa disassembly of hot paths. Full control-loop coefficients would require Ghidra (LITBASE-aware).

---

## 1. Platform & Toolchain

| Item | Value |
|---|---|
| SoC | **ESP32-S3** (Xtensa LX7 dual-core, Chip ID 9) |
| Framework | **Arduino-ESP32 2.0.14** (`framework-arduinoespressif32@3.20014.231204`) |
| IDF | ESP-IDF v4.4.6-dirty |
| Build system | **PlatformIO** (dev env `/home/vincent_dev/.platformio/`) |
| Developer handle | `vincent_dev` |
| Built | 2023-10-04 16:40:08 |
| Flash | 16 MB, 80 MHz, DIO |
| Secure boot | **Off** (secure version 0) — flash is plaintext-readable |
| Flash encryption | Off |

**Implication for a re-implementation:** the stock firmware does not rely on any hardware crypto/security. An open firmware can be flashed via standard esptool without any signing/keys.

---

## 2. Memory Map

ESP32-S3 application image, 5 segments:

| # | Type | Load addr | Size | Contents |
|---|---|---|---|---|
| 0 | DROM | `0x3c110020` | 2.65 MB | RO data: strings, LVGL assets/fonts, OTA URLs, cert bundle |
| 1 | DRAM | `0x3fc96990` | 24 KB | Initialized globals (.data) |
| 2 | IRAM | `0x40374000` | 10 KB | Fast IRAM routines (WiFi/BT coex, memcpy) |
| 3 | IROM | `0x42000020` | 1.08 MB | **Main application code** (Xtensa instructions + literal pools) |
| 4 | IRAM | `0x40376988` | 64 KB | IRAM code (entry `call_start_cpu0`, ISRs) |

Entry point: `0x403777d8` (seg 4, standard IDF `call_start_cpu0`).

> Note: IROM contains literal pools interspersed with code and uses the **LITBASE** register for `l32r`, which is why linear-sweep disassemblers (capstone, radare2) choke on it. Ghidra's Xtensa module handles this.

---

## 3. Software Stack (exact libraries)

Identified from embedded `__FILE__` paths:

**Arduino-ESP32 core HAL modules in use:**
`esp32-hal-adc`, `esp32-hal-cpu`, `esp32-hal-gpio`, `esp32-hal-i2c`, `esp32-hal-i2c-slave`, `esp32-hal-ledc`, `esp32-hal-misc`, `esp32-hal-rgb-led`, `esp32-hal-rmt`, `esp32-hal-uart`

**Arduino libraries in use:**
| Library | Purpose |
|---|---|
| `WiFi` + `WiFiAP` + `WiFiSTA` + `WiFiGeneric` + `WiFiUdp` | WiFi client + SoftAP for onboarding |
| `WiFiClientSecure` + `esp_crt_bundle` | TLS for OTA (CA bundle, no client cert) |
| `HTTPClient` | Fetch OTA firmware list |
| `Update` | Write the downloaded firmware.bin |
| `Wire` (I2C) | GT911 touchscreen |
| `FS` + `vfs_api` | VFS / SPIFFS-style storage |
| `Preferences` | NVS key/value wrapper |

**Third-party:**
- **LVGL** (v8 line, many `lib/lvgl/src/...` paths) — the entire UI.
- **GFX Library for Arduino** (`Arduino_ESP32RGBPanel`) — display driver, using the native `esp_lcd_new_rgb_panel` peripheral.
- **AsyncTCP** (`src/AsyncTCP.cpp`) — async TCP for the HTTP server / captive portal.

No MQTT, no AWS/Azure IoT client, no JSON lib other than ad-hoc CSV parsing — this is a **network-isolated** device.

---

## 4. Hardware Interface Map

### Display
- **Type:** Native ESP32-S3 RGB LCD panel (`esp_lcd_rgb_panel`), driven via `Arduino_ESP32RGBPanel::getFrameBuffer()`.
- This is a **parallel RGB** interface (not SPI). Framebuffer lives in PSRAM.
- Resolution: not in strings (likely 480×320 or 320×240, ESP32-S3 RGB typical). *Exact pins need board header / Ghidra.*

### Touch
- **GT911** capacitive touch controller over **I2C**.
  - `"Config GT911 success with 0x%02x!"` / `"Config GT911 failed!"`
  - Standard GT911 I²C address 0x5D/0x14, INT pin configurable.
- A "second peripheral" I2C bus is referenced (`no Default SDA Pin for Second Peripheral`) — possibly for a pressure sensor or NTC expander.

### Sensors & actuators (from log strings)
| Subsystem | Sensor/actuator | Evidence |
|---|---|---|
| Boiler temperature | **NTC thermistor(s)** on ADC | `NTC%i: %iC`, `NTC%i: 100C` |
| Group temperature | NTC (NTC1) | `NTC%i: %iC` (multiple) |
| Pressure | **Pressure transducer** on ADC | `MinPressureADC`, `BarADC`, `Pressure: %.1f => %.1f` |
| Flow | **Flow meter** (pulse/hall) | `Flowrate: %.1f => %.1f`, `FLOW:%.1fMl`, `%.1f ml/s` |
| Paddle/lever | Analog position sensor on ADC | `MinPaddle`/`MaxPaddle`, `PADDLE ARMED AND READY` |
| Pump | PWM-controlled (likely vibratory or VFD) | `9 Bar @ %i`, `Target %.1f BAR` |
| Status LED | **NeoPixel** / addressable RGB | `esp32-hal-rgb-led`, `neopixel` |
| Boiler fill | Solenoid + pump | `HandleFillBoiler`, `Boiler is empty. Start filling.` |
| Water level | Sensor | `NoWaterDetection`, `WaterEmptyFill` |

> Exact GPIO/ADC channel numbers are set by `pinMode`/`analogReadPin` literals in IROM — recoverable via Ghidra, not from strings.

---

## 5. Sensor Calibration Models

The firmware uses **linear regression** for every analog sensor (slope/intercept stored in NVS):

```
Pressure (bar) = (ADC - MinPressureADC) * PressureSlope + PresIntercept
Temperature (°C) = ADC * tempSlope + tempIntercept
```

Calibration log strings:
- `Pressure: Min: %i Slope: %.4f Offset: %.4f`
- `tempSlope: %.4f, tempIntercept: %.4f`
- `Slope: %.4f, Intercept: %.4f`
- `Paddle 9 Bar @ %i` (raw ADC at the 9-bar reference point)

This is trivial to replicate in an open firmware: store two floats per sensor in NVS, apply on read.

---

## 6. Persistent Storage (NVS) Schema

Three logical namespaces, accessed via Arduino `Preferences`:

### `MachineInfo` (device identity — read-only after factory)
| Key | Type | Meaning |
|---|---|---|
| `ModelName` | str | `KD360X` |
| `GlobalRegion` | str | region code (DE/EU/US/...) |
| `SW_VersionMajor/Minor/Patch` | u8 | firmware version (1.1.54) |

### `Calibration` (per-unit factory calibration)
| Key | Type | Meaning |
|---|---|---|
| `MinPaddle` | int | paddle ADC at rest position |
| `MaxPaddle` | int | paddle ADC at full-engage |
| `MinPressureADC` | int | pressure transducer zero offset |
| `PressureSlope` | float | bar per ADC count |
| `PresIntercept` | float | pressure offset |
| `BoilerWord` | u32 | bitmask of boiler state/flags (`NVS Boiler word: 0x%X`) |
| (`tempSlope`/`tempIntercept`) | float | NTC calibration (in calibration screen) |

### `Settings` (user preferences)
Contains: temperature setpoint (`SetTemp`), single/double shot volumes, brew profile pointer, energy mode (`Sleep Mode`/`Energiesparmodus`), pinned coffee, steam-clean counter (`Total Steam Clean Times`), `PrivacyPolicy` accepted flag.

### `CoffeeProfile` / `Record` / `UserData` (user data)
- `CoffeeProfile` — array of user-defined pressure/flow curves (`Size of curve profile: %i bytes`).
- `Record` — brew history (`Size of record: %i bytes`, `No record found`).
- `WifiData` — last SSID/password (`SSID: %s, Password: %s Privacy Policy: %i`).

---

## 7. Brew Control State Machine

Implemented in `src/StateControl.cpp` + `src/FunctionRoutine.cpp`. Entry: `StartMakeCoffee` → `CheckPaddleAction` → mode dispatch.

### Brew modes
| Internal name | UI label | Behavior |
|---|---|---|
| `PrepareDripCoffee` | "Prepare Dripcoffee Control" | Drip-coffee flow mode |
| `PrepareManualFlow` | "Prepare Manual Flow Control" | Manual flow-rate control |
| `PrepareManualVPS` | "Prepare Manual VPS" | Full manual pressure profiling |
| `PrepareSemiManualVPS` (no preinfusion) | "Prepare Semi Manual w/o PreInfusion" | Semi-manual, direct to 9 bar |
| `PrepareSemiManualVPS` (w/ preinfusion) | "Prepare Semi Manual w/ PreInfusion" | Semi-manual with preinfusion ramp |
| `PrepareProfileCoffee` | "Prepare Profile Coffee" | Replay a stored user profile |
| `PrepareHistoryCoffee` | "Prepare History Coffee" | Replay a past shot from history |
| Classic 9 Bar | "Classic 9 Bar" | Fixed 9-bar extraction |

### Mode flow (paddle-driven)
```
PADDLE ARMED AND READY
   │  CheckPaddleAction → IsPaddleWithinRange
   ▼
Prepare<Mode>  ──► Pressure: %.1f => %.1f   (closed-loop to Target %.1f BAR)
              ──► Flowrate: %.1f => %.1f    (volumetric control)
   │
   ├──@Paddle value out of range  → abort
   ├──Paddle PAGE3-BREW           → hold (BrewingHold / ExitBrewingHold)
   ├──Paddle BrewHold             → pause extraction
   └──release @ %d (Max: %d) KickoffPaddle: %d  → end shot
```

### VPS (Volumetric Profiling System)
The headline feature. A profile is a **pressure-vs-time / flow-vs-time curve**:
- `Size of curve profile: %i bytes`
- `VPS-%iS` — named profile variant
- Profiles created via `ui_AddProfilePage1/2/3` + `ui_AddFlowProfilePage2`, edited via `EditSelection`, saved as `SAVE_AS_NEW`/`SAVE_OVERWRITE`/`SAVE_RENAME`.

### Shot presets (volumetric)
Single and Double presets store **both** time and volume targets (cross-validated):
- `SAVE SINGLE SHOT TO %i SEC (%i ML)`
- `SAVE DOUBLE SHOT TO %i ML (%i SEC)`

---

## 8. Boiler & Water Management (`src/HandleFillBoiler.cpp`)

Startup fill sequence with safety interlocks:

```
Boiler is empty. Start filling.
   │
   ├── boiler fill via pump/solenoid
   │
   ├─► "Boiler is full (Sensor stable for 2s)"   ← primary end condition
   ├─► "Boiler filling volume reached."           ← volumetric limit
   ├─► "Boiler filling maximum time reached."     ← timeout safety
   └─► "Skip boiler filling because magic bypass detected."  ← service bypass

NVS Boiler word: 0x%X   ← status bitmask persisted
```

Water-out handling: `NoWaterDetection` → `ui_WaterEmptyFill` screen → `Start Fill Water` / `Stop At`.
Drainage: `GOTO DRAIN MODE` → `Start Drainage` (`ui_Drainage`).

---

## 9. OTA Update Protocol

### Endpoints (both Azure, tried in order — failover)
1. `https://wpmfirmwareprod.blob.core.windows.net/firmware/` (Blob origin)
2. `https://wpm-firmware-updates-g9hfehacbzaphxfj.a01.azurefd.net/firmware/` (Front Door CDN)

### Firmware list format
Fetched as a **plain-text CSV** at `KD360X/KD360X_FW_lists.txt`. Live (verified 2026-06-24):

```
<version>,<region>,<date YYYYMMDD>,<filename>
1.1.54,DE,20260623,KD360X_OTA_1.1.54_DE_20260623.bin
1.1.54,3C,20260623,KD360X_OTA_1.1.54_3C_20260623.bin
...
9.9.9,FW,20260317,firmware.bin
```

**Regions seen:** `SW, RU, HK, 3C, DE, KR, EU, US, TW, FR, FW`
(`3C` = China, `FW` = factory/recovery, `SW` = Switzerland)

### Client logic (`parseAndCheckFirmware`, `runOTA`, `OTA_Routine`)
1. GET `KD360X/KD360X_FW_lists.txt` from server 1; on HTTP error → try server 2 (`Failed to fetch from server %d, HTTP code: %d. Trying next server...`). Both fail → `All servers failed. Please check network or server status.`
2. Parse each CSV line; match line where `<region>` == device `GlobalRegion`.
3. Compare `<version>` vs local using `%d.%d.%d` (major.minor.patch). Display `">>> NEW UPDATE AVAILABLE! <<<"` or `"Current firmware is up to date for this region."`
4. **Downgrade lock:** a region cannot go below its highest-ever version (`Once a higher version is flashed, a lower version of ... cannot be installed`). The `9.9.9,FW` entry with artificially-high version is a **forced factory/recovery image** that always wins the compare.
5. Filename built as `%s V%s_%s` → `KD360X/<filename>`; GET over HTTPS, stream into `Update` (ESP HTTPS OTA):
   - `OTA update started!` → `OTA Progress Current: %u bytes, Final: %u bytes` → `OTA update finished successfully!` / `There was an error during OTA update!`
6. URL on device shown as `Selected firmware: %s`, `Date: %s`, `File to download: %s`.

### Security posture
- TLS with embedded CA bundle (`esp_crt_bundle`), server verified. **No client cert, no SAS token, no embedded secrets.** Clean.
- No signature verification beyond the image hash — flash is trusted. An attacker controlling DNS + the Azure endpoint could push firmware, but there's no key material to steal from the device.

---

## 10. WiFi Onboarding

- **No BLE provisioning.** Uses classic **SoftAP + captive portal**.
- Device opens SoftAP `esp32s3-<something>` (`"Initialising Soft Access Point..."`, SoftAP SSID prefix `esp32s3-`).
- An async HTTP server (`AsyncTCP` + `HTTP server started`) serves a captive portal (`text/html`, `image/svg+xml`, `text/xml`) — the `ui_ScanWifi` / `ui_EnterWifiPassword` flow.
- User scans, selects SSID, enters password on the on-screen `ui_Keyboard`.
- Credentials stored in NVS `WifiData`; reconnection diagnostics surface friendly causes:
  - `Authentication failed. Likely a wrong password.`
  - `Access Point not found. Check SSID spelling or signal strength.`
  - `4-way handshake timeout. Often caused by wrong password or weak signal.`
  - `Beacon timeout. The ESP32 can no longer hear the router.`
- A **QR code** deep-link (`Scan QR code to access OTA panel`) lets a phone open the OTA screen directly.

---

## 11. UI Architecture (LVGL)

22 screens under `src/ui/screens/`, each with an `ui_<Name>` init function and `ui_event_<Name>` handler. Navigation is event-driven via the central `StateControl` (`GOTO ...` actions).

### Screen catalog
| Screen | Purpose |
|---|---|
| `ui_MainPage` | Home / mode selection |
| `ui_Keyboard` | On-screen text entry (WiFi pwd, naming) |
| `ui_ScanWifi` / `ui_EnterWifiPassword` | WiFi onboarding |
| `ui_9BarMode` / `ui_9BarPreInfusion` / `ui_9BarPressure` | Classic modes |
| `ui_SemiBrewing` / `ui_SemiManual_VPS_Sequence` | Semi-manual VPS |
| `ui_BrewingHold` / `ui_BrewingFlow` / `ui_BrewingFlowHold` | Active brew screens |
| `ui_AddProfilePage1/2/3`, `ui_AddFlowProfilePage2` | Profile editor wizard |
| `ui_ProfileList` / `ui_ViewUserProfile` / `ui_EditSelection` | Profile management |
| `ui_SetPinned` | Pinned/favorite coffee |
| `ui_TunningCups` | Single/Double volumetric tuning |
| `ui_SetTemperature` | Boiler temp setpoint |
| `ui_Preheating` | Warm-up screen |
| `ui_PaddleMonitor` | Live telemetry (paddle/pressure/flow) |
| `ui_CalibratePressure` / `CalibratePaddle` | Calibration |
| `ui_Drainage` / `ui_WaterEmptyFill` | Maintenance |
| `ui_ResetMenu` | Factory reset / wipe NVS |
| `ui_OTAUpdater` | Firmware update |
| `ui_GodDoor` / `ui_GodMode` | **Service menu** (see §12) |

### Localization
Every user-visible string exists in **English + German** (this is the DE build). Region builds presumably swap the German column. `ui_9BarMode` etc. are bilingual key lookups.

---

## 12. Service / Engineer Menus

### `ui_GodDoor` — the entry door
Hidden screen that branches to all engineering modes:
- `GOTO GOD MODE` → full service dashboard (`ui_GodMode`)
- `GOTO FACTORY MODE`
- `GOTO DRAIN MODE` / `GOTO PADDLE MODE` / `GOTO PRESSURE MODE`
- `GOTO PADDLE MONITOR` → live sensor telemetry
- `GOTO GOD SEVRER MODE` (sic — typo in firmware) → likely a SoftAP service mode
- `GOTO SELECT REGION` → change device region
- `GOTO OTA MODE` / `GOTO FONT SHOWCASE`
- `RESET TO FACTORY DEFAULT`, `WIPE NVS`

### `ui_GodMode` — service actions
Contains a debug action dispatcher (the `EDIT:` strings are its internal log tags):
- `EDIT:SAVE_WITHOUT_gc`
- `EDIT:SAVE_OVERWRITE_gc`
- `EDIT:SAVE_AS_NEW_gc`
- `EDIT:SAVE_RENAME_gc`
- `EDIT:MAGIC_STOREAGE (DELETE)` — **deletes the calibration/storage** (the "magic bypass" referenced by boiler fill: `Skip boiler filling because magic bypass detected.`)
- `EDIT: UNKNOWN ACTION CAUGHT!`

Reaching `GodDoor` from the UI requires a hidden tap-sequence passcode handled in
`ui_GodDoor.cpp`. The specific unlock sequence is **not documented in this
project** (see the legal/ethics note in `PRIMUS_RE_RECOVERED.md §F`); it is
recoverable from the firmware by anyone with a legitimate repair need via the
`ui_event_GodDoor` decompilation.

---

## 13. Boot Sequence (reconstructed)

```
call_start_cpu0 (0x403777d8)            ← IDF entry
   ├── init clocks, watchdog, flash
   ├── nvs_flash_init  →  load MachineInfo / Calibration / Settings
   ├── init display (esp_lcd_rgb_panel) + LVGL
   ├── init GT911 touch over I2C
   ├── init sensors (ADC channels: pressure, paddle, NTC)
   ├── StateControl::init  →  show ui_MainPage (or ui_Preheating)
   ├── HandleFillBoiler  →  fill boiler until full/volume/timeout
   ├── (if WiFi creds) WiFiSTA::begin  →  reconnect logic
   └── loop: FunctionRoutine + LVGL timer tick + async OTA check
```

---

## 14. Open-Source Firmware — Recommended Architecture

Based on the above, a clean reimplementation should target:

```
primus-os/
├── platformio.ini          # esp32-s3, 16MB, Arduino-ESP32 >=2.0.14 (or IDF 5.x)
├── src/
│   ├── main.cpp
│   ├── hal/
│   │   ├── display.*       # esp_lcd RGB panel + LVGL (port the framebuffer API)
│   │   ├── touch.*         # GT911 over I2C (Wire)
│   │   ├── sensors.*       # pressure/paddle/NTC ADC + linear calibration
│   │   ├── pump.*          # LEDC PWM output
│   │   └── flow.*          # pulse-counter input (pcnt) for flow meter
│   ├── control/
│   │   ├── state_control.* # mode state machine (§7)
│   │   ├── pid.*           # pressure + temperature PID
│   │   ├── vps.*           # volumetric profile engine (time/flow/pressure curves)
│   │   └── boiler.*        # fill + safety interlocks (§8)
│   ├── storage/
│   │   ├── nvs.*           # replicate §6 schema (MachineInfo/Calibration/Settings/Profile)
│   │   └── profile.*       # curve serialization
│   ├── net/
│   │   ├── wifi.*          # STA + SoftAP onboarding (captive portal)
│   │   └── ota.*           # CSV list parser + HTTPS OTA (can reuse WPM endpoints or self-host)
│   └── ui/                 # LVGL screens (22) — reimplement from §11
└── docs/
    └── PRIMUS_FIRMWARE_INTERNALS.md  ← this file
```

### What we can replicate directly
- NVS schema (§6) — exact same keys for calibration portability.
- Sensor calibration math (§5) — linear slope/intercept.
- OTA protocol (§9) — CSV list + HTTPS, can point at WPM endpoints or a community mirror.
- State machine modes (§7) — same mode set.
- LVGL screen list (§11) — same UX.

### What still needs hardware probing
- Exact GPIO/ADC channel/pwm-channel assignments (use a logic analyzer + `analogRead` pin sweep, or Ghidra on the `pinMode`/`ledcSetup` calls).
- RGB panel timing (hsync/vsync/pclk polarity) and resolution — from the panel datasheet or `esp_lcd_panel_config_t` struct in IROM.
- GT911 INT/RST pin + I2C bus number.
- PID gains inside `StateControl`'s inner control loop.

---

## 15. Tools Used & Reproducibility

All artifacts in `analysis/`:

```
analysis/
├── extracted/seg0-4_*.bin       # 5 segments (esptool image_info + manual parse)
├── strings_seg*.txt             # ASCII strings per segment (~9.2k lines)
├── docs/
│   ├── PHASE1_OVERVIEW.md             # phase-1 overview
│   ├── PRIMUS_FIRMWARE_INTERNALS.md   # architectural documentation
│   └── PRIMUS_RE_RECOVERED.md         # Ghidra decompilation findings
└── scripts/                           # reproducible analysis toolchain
    ├── disasm.py                      # Capstone-6 Xtensa disassembler
    └── xref.py                        # string→code cross-reference
```

To reproduce the deep disassembly of control loops, install **Ghidra ≥ 11** with the ESP32-S3 processor (it correctly resolves LITBASE `l32r`), load `seg3_IROM` at `0x42000020` and `seg4_IRAM` at `0x40376988`, and run auto-analysis. The rich log strings make every function self-identifying.
