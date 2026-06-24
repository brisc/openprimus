# WPM Primus (KD360X) — Firmware Reverse-Engineering

> **Goal:** full understanding for an open-source alternative firmware.
>
> Source binary: `KD360X_OTA_1.1.54_DE_20260623.bin` (DE region, v1.1.54, built 2023-10-04)
> Method: esptool parse → 5-segment extraction → static string/symbol analysis → **Ghidra 12.1.2 headless decompilation** (Xtensa module, all segments in one unified address space).

See `PRIMUS_FIRMWARE_INTERNALS.md` for the overview; this file holds the **decompilation-recovered details** (pin assignments, calibration constants, control logic, service-menu architecture).

---

## A. Platform (confirmed by decompilation)

- **ESP32-S3**, Xtensa LX7, Arduino-ESP32 2.0.14 (`framework-arduinoespressif32@3.20014.231204`) on IDF 4.4.6.
- Developer env: PlatformIO under `/home/vincent_dev/.platformio/`.
- Build banner recovered from `main()`: `"Booting..."`, build date strings `"Jun 23 2026"` / `"16:57:43"`.
- PSRAM present: log string `"PSRAM: %iMB"` (`main()` line ~550, `uVar21 >> 0x14` = MB count).

---

## B. Hardware Pin Map (from `main()` / `setup()` decompilation)

Decompiled function `setup()` @ `0x4200b9d4` and `init()` @ `0x42009ec8`.

### Touch (GT911) — I2C
```c
Wire.begin(SDA = 6, SCL = 5);                 // FUN_42024450(Wire, 6, 5, ...)
// GT911 dual-address probe:
//   try I2C addr 0x5D  → success("Config GT911 success with 0x%x!")  (0xba response)
//   else try 0x14      → success (0x28 response)
//   else: "Config GT911 failed!"
```
- **GPIO 5 = SCL, GPIO 6 = SDA** (I2C bus 0).
- GT911 INT/RST pins are toggled via `pinMode` — INT likely on a GPIO near the probe sequence (recovered value `0x14`=20 / `0x5d`=93 context; verify on hardware).

### GPIO configured in `main()` via `pinMode` (`FUN_420654d4(pin, mode)`)
```c
pinMode(4,  OUTPUT);         // GPIO4  out   — likely pump PWM or solenoid
pinMode(19, OUTPUT);         // GPIO19 out   — likely pump PWM or solenoid (0x13)
pinMode(42, INPUT_PULLUP);   // GPIO42 in    — switch/sensor (0x2a)
pinMode(20, INPUT);          // GPIO20 in    — analog/sensor (0x14)
```

### ADC (analog reads during setup — `FUN_420652d0(pin)` = `analogRead`)
```c
analogRead(4);   // → stored as float /1.0 at settings+0x2c, +4   (pressure baseline?)
analogRead(19);  // → stored as float /1.0 at settings+100        (paddle baseline?)
```
GPIO4 and GPIO19 (ADC-capable on S3) are the **pressure transducer** and **paddle position** analog inputs (exact assignment confirmed by sensor role in StateControl).

### Display — native RGB panel
- `gfx->begin()` (Arduino_GFX `Arduino_ESP32RGBPanel`), backed by ESP-IDF `esp_lcd_new_rgb_panel`.
- Framebuffer in PSRAM. RGB parallel bus pins are set in the GFX driver constructor (not in `main`), so exact R/G/B/Hsync/Vsync/PCLK pins need the GFX config struct — board-specific; will differ per clone.
- `*DAT_4200062c = 0x14` and various `0x30` writes set backlight/timing after `begin()`.

### NeoPixel
- Addressable RGB LED via `esp32-hal-rgb-led` / `neopixel` (status indicator).

> **Still to confirm on hardware:** exact RGB-panel data pins, GT911 INT/RST pins, pump PWM channel (LEDC) vs solenoid (on/off), flow-meter input (PCNT). These are in the GFX driver constructor and `ledcSetup` literals; recoverable by decompiling `setup()` further or probing the board.

---

## C. NVS Storage Schema & Default Values (fully recovered)

From `init()` @ `0x42006c7c`. Namespaces are Arduino `Preferences`.

### `WifiData`
| Key | Default | Notes |
|---|---|---|
| `SSID` | *(factory test SSID)* | Hardcoded factory-programming SSID; blank on shipping units |
| `Password` | *(factory test password)* | Hardcoded factory-programming password |

> The stock firmware carries **hardcoded factory-programming WiFi credentials** as NVS
> defaults (used on the assembly bench before first user setup). The actual values are
> intentionally **not reproduced here** — they are identifiable operational credentials
> of the manufacturer and not needed for an open-source reimplementation. They are
> recoverable from the firmware if ever required for a specific repair scenario.

### `Calibration`
| Key | Type | Default | Notes |
|---|---|---|---|
| `MinPaddle` | u16 | **2000** | paddle ADC at rest |
| `MaxPaddle` | u16 | **4000** (0xfa0) | paddle ADC at full-engage |
| `MinPressureADC` | u16 | **540** (0x21c) | pressure zero offset |
| `PressureSlope` | float | **0.008** | bar per ADC count |
| `PresIntercept` | float | **-3.0246** | pressure offset |

### `MachineInfo`
| Key | Default |
|---|---|
| `ModelName` | `KD360X` |
| `SW_VersionMajor` | `1` |
| `SW_VersionMinor` | `1` |
| `SW_VersionPatch` | `0x36` (54) → **1.1.54** |
| `GlobalRegion` | `3` (= `REGION_DE`) |

### `Settings`
- `Record` blob at `DAT_420000a8`, **0x5f = 95 bytes** per record.
- `BoilerWord` u16 (default 0).

### `UserData`
- `CoffeeProfile` blob: **7250 bytes** (0x1c52) total.
- `HistoryProfile` blob: **0x5aa = 1450 bytes**.

---

## D. Sensor Math (calibration formulas — confirmed)

```
Pressure(bar) = (ADC - MinPressureADC) * PressureSlope + PresIntercept
            = (ADC - 540) * 0.008 + (-3.0246)

Temperature(°C) = ADC * tempSlope + tempIntercept     // NTC, values in Calibration screen
```
Log format strings confirm: `"Pressure: Min: %i Slope: %.4f Offset: %.4f"`, `"tempSlope: %.4f, tempIntercept: %.4f"`.

Flow: volumetric from flow-meter pulses → `"%.1f ml/s"`, `"Flowrate: %.1f => %.1f"`.

---

## E. Boiler Fill Algorithm (`HandleFillBoiler` @ `0x420063d8`)

Recovered state machine:
```c
// guard
if (magic_bypass)  return 2;                       // service skip

// sensor-stable debounce: counter must reach thresholds
if (water_level_ok) {
    stable_cnt++;
    if (stable_cnt >= 4)   → "Boiler is full (Sensor stable for 2s)"  (DONE)
} else {
    low_cnt++;
    if (low_cnt >= 10)     → "Boiler is empty. Start filling."        → FUN_4200639c() start pump
}

// fill termination
volume = (current_reading - baseline) * 100 / scale;
if (volume < 100) {
    target -= 0x14;                                  // ramp down
    if (target > 0) return 1;                        // keep filling
    → "Boiler filling maximum time reached."         (0xe2, timeout)
} else {
    → "Boiler filling volume reached."               (0xd2, volume met)
}
stop pump (FUN_42006374); BoilerWord persisted; return 2;
```
Debounce constants: **4 ticks** (full), **10 ticks** (empty), volume threshold **100** (scaled units), timeout decrement **0x14** per tick.

---

## F. GodDoor Service Menu (`ui_event_GodDoor` @ `0x42016f00`)

The firmware contains a hidden **service menu** ("GodDoor") reachable from the
UI, which branches to engineering modes: GOD MODE (service dashboard), FACTORY
MODE, DRAIN/PADDLE/PRESSURE test modes, OTA MODE, REGION SELECT, and a
factory-reset / NVS-wipe action.

The menu is gated by a **6-entry tap-sequence passcode** with a 3-attempt
lockout. The entry values are encoded gesture identifiers (not plain digits);
the device compares the entered sequence against a table of expected values and
dispatches to the selected mode on a match.

> **The specific passcode values and unlock gesture are intentionally not
> documented here.** A service menu's existence is a normal architectural fact,
> but publishing the exact bypass sequence crosses from "documenting how my own
> device works" into "publishing a service-mode bypass," which is unnecessary
> for the open-firmware goal and raises the legal/ethical risk without benefit.
> It remains recoverable from the firmware by anyone with a legitimate need
> (e.g. a repair technician with the device in hand) via the `ui_event_GodDoor`
> decompilation.

### GodMode `EDIT:` actions (`ui_GodMode`)
The service dashboard exposes profile save variants (`SAVE_WITHOUT`,
`SAVE_OVERWRITE`, `SAVE_AS_NEW`, `SAVE_RENAME`) and a storage-wipe action
("MAGIC_STOREAGE (DELETE)") that clears calibration — this is what enables the
"magic bypass" flag that skips the boiler fill check during bench testing.
- `UNKNOWN ACTION CAUGHT!` — fallback

---

## G. Brew State Machine (`StateControl` @ `0x4200891c`)

39597 chars decompiled — the largest app function. Dispatch (`GOTO ...` targets recovered):
- `PADDLE ARMED AND READY` → `CheckPaddleAction` → `IsPaddleWithinRange`
- Modes: `PrepareDripCoffee`, `PrepareManualFlow`, `PrepareManualVPS`, `PrepareSemiManualVPS` (±preinfusion), `PrepareProfileCoffee`, `PrepareHistoryCoffee`, "Classic 9 Bar"
- Closed loop logs: `"Pressure: %.1f => %.1f"`, `"Flowrate: %.1f => %.1f"`, `"Target %.1f BAR"`
- Release detection: `"RELEASE @ %d (Max: %d) KickoffPaddle: %d"`

(Full PID gains are in the function body constants — recoverable by decompiling the inner control loop; the log lines above locate it.)

---

## H. OTA Protocol (verified live + decompiled `parseAndCheckFirmware` @ `0x4201cc2c`)

```
GET https://wpmfirmwareprod.blob.core.windows.net/firmware/KD360X/KD360X_FW_lists.txt
   (failover) https://wpm-firmware-updates-g9hfehacbzaphxfj.a01.azurefd.net/firmware/KD360X/KD360X_FW_lists.txt
```
Live list format (verified 2026-06-24):
```
<ver>,<region>,<date YYYYMMDD>,<filename>
1.1.54,DE,20260623,KD360X_OTA_1.1.54_DE_20260623.bin
9.9.9,FW,20260317,firmware.bin          ← factory/recovery (always wins version compare)
```
Client: parse CSV → match device `GlobalRegion` → compare `major.minor.patch` → stream-download `firmware.bin` over HTTPS into `Update`. Downgrade-locked per region.

---

## I. Function Map (recovered via string x-refs, stripped binary)

| Address | Name | Source |
|---|---|---|
| `0x4200b9d4` | `setup()` | `src/main.cpp` |
| `0x42009ec8` | `init HardwareState / GT911+gfx` | `src/main.cpp` |
| `0x42006c7c` | `init NVS defaults` | `src/main.cpp` |
| `0x4200891c` | `StateControl` | `src/StateControl.cpp` |
| `0x420063d8` | `HandleFillBoiler` | `src/HandleFillBoiler.cpp` |
| `0x42003b94` | `FunctionRoutine` | `src/FunctionRoutine.cpp` |
| `0x42016f00` | `ui_event_GodDoor` | `src/ui/screens/ui_GodDoor.cpp` |
| `0x4201cc2c` | `parseAndCheckFirmware` | `src/ui/screens/ui_OTAUpdater.cpp` |
| `0x420654d4` | `pinMode` | Arduino HAL |
| `0x420652d0` | `analogRead` | Arduino HAL |
| `0x42024450` | `Wire.begin` | Arduino HAL |
| `0x42063488` | `i2c probe/read` | Arduino HAL |
| `0x4203a648` | `logf(level, file, line, tag, fmt, ...)` | app logging |

6356 functions total; 100+ named by string x-ref; full `function_names.txt` in `analysis/ghidra_out/`.

---

## J. Reproduction / Tooling

Everything is reproducible via Docker Ghidra (no local install):
```bash
# 1. extract segments
python3 -m esptool image_info KD360X_OTA_*.bin
# (segment extraction script in analysis/)

# 2. unified Ghidra project (all segments, one address space)
docker run --rm -u $(id -u):$(id -g) \
  -v "$PWD/ghidra_project:/project" \
  -v "$PWD/ghidra_scripts:/scripts:ro" \
  -v "$PWD/analysis/extracted:/data:ro" \
  blacktop/ghidra:latest \
  /ghidra/support/analyzeHeadless /project PrimusProject \
    -import /data/_seed.bin -processor Xtensa:LE:32:default \
    -loader BinaryLoader -loader-baseAddr 0 -overwrite \
    -postScript ImportPrimus.java      # adds the 5 real blocks

# 3. dump xrefs / decompile
... -process _seed.bin -postScript DumpPrimus.java
... -process _seed.bin -postScript DecompileFuncs.java   # ADDRS=hex,hex,...
```
Outputs: `ghidra_out/{functions.txt, strings_xrefs.txt, data_xrefs.txt, function_names.txt, decompiled.c}`.

---

## K. Open-Source Firmware — Status of Knowledge

| Subsystem | Known? | Source |
|---|---|---|
| Platform/toolchain | ✅ exact | build strings |
| NVS schema + defaults | ✅ exact | `init()` decompile |
| Sensor calibration math | ✅ exact | `init()` + log strings |
| Boiler fill algorithm | ✅ exact | `HandleFillBoiler` decompile |
| Brew mode state machine | ✅ structure | `StateControl` decompile (PID gains = TODO) |
| OTA protocol | ✅ exact | live + decompile |
| WiFi onboarding | ✅ | SoftAP + captive portal |
| Touch (GT911) | ✅ I2C addr + bus | pins 5/6 |
| GPIO/ADC roles | ⚠️ partial | 4 pins confirmed; full map needs hardware probe |
| RGB panel pins | ⚠️ | in GFX driver ctor (board-specific) |
| GodDoor unlock | ⚠️ mechanism known | exact tap values need HW brute-force |
| Pump PWM (LEDC) | ⚠️ | `ledcSetup` literals; channel/freq = HW probe |
