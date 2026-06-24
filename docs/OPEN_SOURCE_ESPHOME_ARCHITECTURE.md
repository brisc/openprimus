# Open-Source Firmware — ESPHome Architecture

> How to build **`primus-os`**, an open-source replacement firmware for the WPM
> Primus (KD360X) espresso machine, on **ESPHome 2026.6**.

This document captures the target architecture, the recovered hardware facts it
rests on, and the ESPHome-specific component choices. It is written so a
contributor can start implementing without re-deriving the pinout or re-reading
the reverse-engineering notes.

> **Source of truth for the RE facts:** `docs/PRIMUS_RE_RECOVERED.md` and the
> Ghidra decompilation under `ghidra_out/`. Everything marked ✅ below is taken
> directly from the decompiled stock firmware; ⚠️ means it needs a hardware probe.

---

## 1. Platform decision: ESPHome 2026.6 + a custom C++ component

We will **not** clone the stock firmware's raw Arduino/IDF approach. Instead:

| Layer | Technology | Why |
|---|---|---|
| **Platform / build** | ESPHome 2026.6, `framework: esp-idf` | Parallel RGB + PSRAM *require* ESP-IDF (not Arduino). ESPHome 2026.6 ships IDF 5.3.2 — newer than the stock 4.4.6. |
| **Declarative config** | `esphome/primus.yaml` | Display, touch, ADC, LEDC, WiFi, OTA, web UI, LVGL — all configured declaratively. |
| **UI** | ESPHome `lvgl` component | Native LVGL binding; rebuild the 22 screens declaratively + small C++ helpers. |
| **Real-time brew control** | Custom C++ component `primus_brew` (FreeRTOS task) | The pressure/flow profiling loop needs tighter than ESPHome's ~7–16 ms main loop. |
| **Calibration / state** | `preferences` + the recovered NVS schema | Binary-compatible with stock calibration values. |

**Why this split:** ESPHome gives us, for free, the ~90 % of the firmware that is
plumbing (display, touch, WiFi, OTA, sensor drivers). The only piece that needs
hand-written real-time C++ is the brew control loop — exactly where ESPHome's
main-loop timing is too loose. This is the documented ESPHome escape hatch
([Component Architecture](https://developers.esphome.io/architecture/components/)).

---

## 2. Hardware map (recovered from stock firmware decompilation)

### Display — ✅ driver known, ⚠️ data pins need probe
- **Type:** native ESP32-S3 RGB LCD panel, 16-bit parallel RGB.
- **Stock driver:** `Arduino_ESP32RGBPanel` → `esp_lcd_new_rgb_panel()` (confirmed via decompilation).
- **ESPHome driver:** **`mipi_rgb`** (the `rpi_dpi_rgb` driver is deprecated as of 2026.6 — see `docs/` notes).
- **Framebuffer:** PSRAM-backed (stock logs `"PSRAM: %iMB"`).
- ⚠️ **The ~20 RGB data/timing pins** (R0–R4, G0–G5, B0–B4, HSYNC, VSYNC, PCLK, DE) live in the GFX driver constructor, which the decompiler could not surface cleanly. **Action: probe with a logic analyzer / read the PCB silkscreen.** Until then, the YAML `data_pins:` block below is a placeholder.

### Touch — ✅ fully recovered
```c
// stock: Wire.begin(SDA = 6, SCL = 5)
// stock: probe GT911 at I²C 0x5D, then 0x14
```
- **SDA = GPIO6, SCL = GPIO5** (I²C bus 0).
- Controller: **GT911** (native ESPHome `gt911` component).

### Sensors & actuators — ✅ pin roles, ⚠️ exact assignments
From `setup()` decompilation (`FUN_420654d4` = `pinMode`, `FUN_420652d0` = `analogRead`):

| GPIO | Direction | Stock role | ESPHome component |
|---|---|---|---|
| 4 | OUTPUT | actuator (pump PWM / solenoid) | `ledc` or `output` |
| 19 | OUTPUT | actuator (pump PWM / solenoid) | `ledc` or `output` |
| 42 | INPUT_PULLUP | switch / sensor | `binary_sensor` (gpio) |
| 20 | INPUT | analog/sensor | — |
| 4 | ADC read | pressure baseline / paddle | `adc` (platform) |
| 19 | ADC read | pressure baseline / paddle | `adc` (platform) |

⚠️ The **pressure-transducer vs paddle** assignment of GPIO4/GPIO19, plus the
**flow-meter input** (likely a PCNT pin) and **NTC** channels, need one more
targeted decompile of the sensor-read functions (or a multimeter probe). The
calibration math is already known (§4).

### NeoPixel — ✅
Addressable RGB status LED via `esp32-hal-rgb-led` / `neopixel` → ESPHome `light` (`neopixelbus` or `fastled`).

---

## 3. Target repository layout

```
primus-os/                          (separate repo, or primus/firmware/)
├── esphome/
│   └── primus.yaml                 ← declarative config (display, touch, sensors, WiFi, OTA, LVGL)
├── components/
│   └── primus_brew/                ← custom component: the real-time brew controller
│       ├── __init__.py              YAML schema + config validation
│       ├── brew_control.h           FreeRTOS task: pressure PID + VPS profile engine
│       ├── brew_control.cpp
│       ├── sensors.h                linear calibration (recovered slope/intercept)
│       ├── sensors.cpp
│       ├── boiler.h                 the recovered fill algorithm
│       ├── boiler.cpp
│       └── automation.h             state machine (modes)
├── data/
│   └── ui/                          LVGL screen definitions / fonts
├── README.md
└── platformio.ini or esphome build config
```

ESPHome resolves `components/primus_brew` automatically when listed in `primus.yaml`
under `external_components` or via the local `custom_components` mechanism.

---

## 4. Calibration math (binary-compatible with stock NVS)

Recovered defaults from `init()` (`FUN_42006c7c`) — store these in NVS with the
**same keys** so a flashed `primus-os` reads the unit's factory calibration:

```cpp
// sensors.cpp — direct port of stock formulas
float read_pressure_bar(uint16_t adc) {
    // NVS 'Calibration': MinPressureADC=540, PressureSlope=0.008, PresIntercept=-3.0246
    return (adc - min_pressure_adc) * pressure_slope + pressure_intercept;
}
float read_temp_c(uint16_t adc) {
    // NTC: linear regression
    return adc * temp_slope + temp_intercept;
}
```

NVS namespaces to mirror (`Preferences`): `MachineInfo`, `Calibration`, `Settings`,
`UserData` (`CoffeeProfile` blob = 7250 B, `HistoryProfile` = 1450 B, `Record` = 95 B/entry).
Full schema in `docs/PRIMUS_RE_RECOVERED.md §C`.

---

## 5. Brew control loop (the custom component)

The stock `StateControl` runs modes (`PrepareManualVPS`, `PrepareSemiManualVPS`,
`Classic 9 Bar`, …) as a paddle-driven state machine with closed-loop pressure/
flow control (`"Pressure: %.1f => %.1f"`, `"Target %.1f BAR"`).

In `primus-os` this becomes a `Component` with its own FreeRTOS task:

```cpp
// brew_control.h
class PrimusBrew : public Component, public EntityBase {
  void setup() override;        // spawn the RTOS task, load calibration from NVS
  void loop() override;         // non-RT housekeeping (ESPHome main loop)
  void start_shot(Mode mode, Profile *p);
  void stop_shot();
 private:
  static void rtos_task(void *); // the tight ~1 kHz pressure/flow loop
  Mode mode_; Profile *profile_;
};
```

- **Tight loop** (the `rtos_task`): read pressure ADC → PID → write pump LEDC duty →
  read flow pulses → enforce profile target. Runs at ~1 kHz, independent of ESPHome's main loop.
- **Mode dispatch** mirrors the stock state machine (§G of the RE doc).
- **Boiler fill** is a separate sub-component (`boiler.cpp`) porting the recovered
  algorithm (debounce 4 ticks full / 10 ticks empty, volume threshold 100, timeout decrement 0x14).

> PID gains are the one remaining unknown — they're constants inside the stock
  `StateControl` inner loop. They need either one more targeted decompile or
  empirical tuning on hardware.

---

## 6. `primus.yaml` skeleton (ESPHome 2026.6)

```yaml
esphome:
  name: primus
  friendly_name: WPM Primus
  build_flags:           # top-level (2026.6 feature), applies to both backends
    - -O2

esp32:
  board: esp32-s3-devkitc-1
  variant: esp32s3
  framework:
    type: esp-idf        # REQUIRED for parallel RGB + PSRAM
  flash_size: 16MB
  flash_mode: dio        # recovered from image header
  flash_frequency: 80mhz

psram: true              # framebuffer lives here

# ---- Touch (GT911) ----
i2c:
  sda: 6
  scl: 5
  scan: true
touchscreen:
  - platform: gt911
    id: primus_touch

# ---- Display (parallel RGB) ----
display:
  - platform: mipi_rgb    # current driver (rpi_dpi_rgb is deprecated)
    id: primus_display
    data_pins:            # ⚠️ PLACEHOLDER — needs HW probe
      red:   [,,]         # R0..R4
      green: [,,,,]       # G0..G5
      blue:  [,,]         # B0..B4
    hsync_pin:
    vsync_pin:
    de_pin:
    pclk_pin:
    # resolution + timing from the panel datasheet once confirmed
    update_interval: never

# ---- LVGL UI ----
lvgl:
  displays: [primus_display]
  touchscreens: [primus_touch]
  # 22 screens rebuilt here / in data/ui/

# ---- Actuators / sensors ----
output:
  - platform: ledc
    pin: 4                # pump PWM (confirm role on HW)
    id: pump_pwm
    frequency: 50Hz       # tune to stock
  - platform: ledc
    pin: 19               # secondary actuator (confirm)
    id: aux_pwm

sensor:
  - platform: adc
    pin: 4                # pressure transducer (confirm vs paddle)
    id: pressure_adc
  - platform: adc
    pin: 19               # paddle position (confirm)
    id: paddle_adc

binary_sensor:
  - platform: gpio
    pin: { number: 42, mode: INPUT_PULLUP }
    id: switch_42

light:
  - platform: neopixelbus # status LED
    type: GRB
    pin: TODO
    num_leds: 1

# ---- Networking & maintenance ----
wifi: { ssid: !secret wifi_ssid, password: !secret wifi_password }
captive_portal: {}
web_server: {}
ota: { platform: esphome }

# ---- The custom brew controller ----
external_components:
  - source:
      type: local
      path: components
primus_brew:
  pump: pump_pwm
  pressure_sensor: pressure_adc
  paddle_sensor: paddle_adc
```

---

## 7. What's done vs. what's blocking a first boot

| Item | Status |
|---|---|
| Platform choice (ESPHome + custom component) | ✅ decided |
| Touch (GT911) config | ✅ ready |
| Calibration math + NVS schema | ✅ ready |
| Boiler fill algorithm | ✅ ready to port |
| Brew mode state machine | ✅ structure ready |
| `primus.yaml` skeleton | ✅ written (this doc) |
| **RGB display data-pin pinout** | ⚠️ **BLOCKER** — needs HW probe (logic analyzer / silkscreen) |
| Pump vs solenoid roles of GPIO4/19 | ⚠️ needs targeted decompile or probe |
| Flow-meter input pin | ⚠️ needs probe |
| PID gains | ⚠️ needs inner-loop decompile or empirical tuning |
| 22 LVGL screens | ⚠️ rebuild work |

**The single gating item for a first display boot is the RGB data-pin map.**
Everything else can proceed in parallel.

---

## 8. Open questions for hardware probing

1. **RGB panel pinout** — 16 data + 4 timing pins. Method: continuity-check each panel FPC
   trace to the ESP32-S3 GPIO pad, or capture the parallel bus with a logic analyzer.
2. **Pressure vs paddle ADC** — which of GPIO4/GPIO19 is which (verify by watching
   readings while manipulating the paddle vs applying pressure).
3. **Flow meter** — locate the pulse input (likely a PCNT-capable GPIO).
4. **NTC thermistor(s)** — confirm ADC channels and the `temp_slope`/`temp_intercept` values.
5. **Pump control** — is GPIO4/19 a PWM'd vibratory pump or a solenoid on/off?

Each can be resolved in minutes with the hardware on a bench. Once #1 is known, the
`mipi_rgb` block can be filled and the device will render.

---

## References
- ESPHome `mipi_rgb` display driver — https://esphome.io/components/display/mipi_rgb/
- ESPHome 2026.6.0 changelog — https://esphome.io/changelog/2026.6.0/
- ESPHome component architecture (loop timing, RTOS tasks) — https://developers.esphome.io/architecture/components/
- Stock firmware RE details — `docs/PRIMUS_RE_RECOVERED.md`
