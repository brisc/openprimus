# Open-Source Firmware — ESPHome Architecture

> How **`openPrimus`** (the open-source replacement firmware for the WPM Primus /
> KD360X espresso machine) is built on **ESPHome 2026.6**.

This is the living architecture document. It reflects the firmware as it
**currently exists** under `firmware/` (implemented sections marked ✅), the
hardware facts it rests on (from the reverse-engineering), and the open work
(marked ⚠️/🚧).

> **Cross-refs:** RE findings → `docs/PRIMUS_RE_RECOVERED.md`. Getting started →
> `firmware/README.md`. Reproducing the analysis → `README.md` (repo root).

---

## 1. Platform decision: ESPHome 2026.6 (esp-idf) + one custom C++ component

We deliberately do **not** clone the stock firmware's raw Arduino/IDF approach.

| Layer | Technology | Status |
|---|---|---|
| **Platform / build** | ESPHome 2026.6.2, `framework: esp-idf` | ✅ |
| **Declarative config** | `firmware/sim.yaml` (sim) / `firmware/primus.yaml` (HW) | ✅ |
| **UI** | ESPHome `lvgl` component, modular `packages/` | ✅ (3 pages built) |
| **Config / telemetry** | Template `number:` + `sensor:` entities | ✅ |
| **Real-time brew control** | Custom C++ component `primus_brew` (FreeRTOS task) | 🚧 deferred |
| **Calibration / state** | `number` entities (`restore_value`) mirroring stock NVS | ✅ (sim) |

**Why ESPHome:** it gives us, for free, the ~90% of the firmware that is plumbing
(display, touch, WiFi, OTA, sensor drivers, HA entity exposure). The only piece
that needs hand-written real-time C++ is the brew control loop — exactly where
ESPHome's ~7–16 ms main loop is too loose.

**Why `esp-idf` framework (not Arduino):** parallel RGB + PSRAM *require* it.
ESPHome 2026.6 ships IDF 5.3.2 — newer than the stock 4.4.6.

---

## 2. Current firmware structure (what's implemented)

```
firmware/
├── sim.yaml                       ✅ simulator entry point (SDL2 host display)
├── primus.yaml                    ✅ real-hardware config (display pins = ⚠️ TODO)
├── packages/                      ← modular, one concern per file
│   ├── common.yaml                ✅ fonts, globals, simulated sensors, touchscreen
│   ├── config.yaml                ✅ 6 HA-controllable number entities
│   ├── topbar.yaml                ✅ WiFi status icon (LVGL top_layer)
│   ├── home_menu.yaml             ✅ Page 1: brew-mode menu + Settings entry
│   ├── brew_page.yaml             ✅ Page 2: brewing screen with live telemetry
│   ├── chart.yaml                 ✅ pressure gauge (arc) + history buffer
│   └── settings_page.yaml         ✅ Page 3: on-device config sliders
├── fonts/                         icon font (fetched, not committed)
└── README.md                      setup + simulator quick-start
```

**Simulator runs today** — `./scripts/run_sim.sh` opens an SDL2 window with the
full UI. No ESP32 needed. The LVGL UI definition is shared between sim and HW;
only the `display:` driver block differs (`sdl` vs `mipi_rgb`).

---

## 3. The config layer (`config.yaml`) — Home Assistant integration

All brew parameters are template `number:` entities: **persisted across reboots**
(`restore_value: true`), **controllable from HA** (appear as sliders in the HA
UI), and editable **on-device** via the settings page (bidirectional).

| Entity id | What | Range | Default |
|---|---|---|---|
| `target_temp` | Boiler target temperature | 80–100 °C | 93.0 |
| `target_pressure` | Extraction target pressure | 1–12 bar | 9.0 |
| `preinfusion_pressure` | Pre-infusion pressure | 0–4 bar | 2.0 |
| `preinfusion_time` | Pre-infusion duration | 0–10 s | 3.0 |
| `shot_target_volume` | Volumetric shot target | 10–60 ml | 36 |
| `shot_target_time` | Timed shot target | 10–60 s | 28 |

In HA these enable automations like "preheat to 95°C at 7am" or "set pressure
profile for a light roast." They mirror the stock firmware's NVS `Settings`
namespace.

---

## 4. The sensor layer (`common.yaml`)

Simulated sensors with realistic espresso physics, all **driven by the config
entities** (so changing a setting changes the sim behavior):

| Sensor | Unit | Behavior |
|---|---|---|
| `sim_pressure` | bar | Ramps through pre-infusion (`preinfusion_pressure` for `preinfusion_time`) then to `target_pressure`. Exponential approach + jitter. |
| `sim_temperature` | °C | Drifts toward `target_temp`. |
| `sim_flow_rate` | ml/s | Derived from pressure (~1.2 ml/s/bar) + noise. |
| `sim_shot_weight` | g | Cumulative integration of flow during a brew. |

> On real hardware, swap these `template` sensors for real `adc` platforms with
> the recovered `calibrate_linear` filters (§6). The config entities + the math
> stay identical.

---

## 5. The UI (`packages/`) — LVGL

Three pages plus a global topbar, built declaratively:

| Page | Widgets |
|---|---|
| `home_page` | Title, 7 brew-mode buttons (each with an icon + label), Settings button |
| `brew_page` | Mode title, phase status, pressure/time/temp/flow readouts, pressure-gauge arc, Start/Stop, Back |
| `settings_page` | Sliders for temp, pressure, pre-infusion pressure, shot volume (each writes back to its `number` entity) |
| `top_layer` | WiFi status icon (shows on every page) |

**Icons** use Material Symbols, merged into the body font via the `extras:` key
(following the [ESPHome LVGL cookbook](https://esphome.io/cookbook/lvgl/)). The
15MB source font is fetched (not committed); ESPHome subsets it to the declared
glyphs at compile time.

**Round-display aware:** pages use `radius: 255` + a bright border to show the
physical screen edge; content is sized to fit inside the circle (computed
heights, 220px-wide widgets, symmetric padding).

### ESPHome/LVGL patterns enforced (hard-won, documented here)

- **Two-phase updates:** compute values in a plain-C++ lambda writing to scratch
  globals, then update widgets via `lvgl.*.update` actions reading those globals.
  Never read sensor state directly inside an `lvgl.*.update` lambda.
- **Page-visibility guard:** always wrap live widget updates in
  `lvgl.page.is_showing: <page>` — updating widgets on a non-loaded page segfaults.
- **NaN guards** on every sensor-derived value (`if (p != p) p = 0.0f;`).
- **Button text-only updates:** `lvgl.button.update` accepts only `text:`, NOT
  `bg_color:` (the latter compiles but crashes at runtime).
- **No `lv_obj_t` in lambdas:** ESPHome YAML lambdas see only a forward-declared
  `lv_obj_t`, so they can't call `lv_canvas_*`/`lv_label_set_text` directly.
  Drawing must use declarative `lvgl.*.update` actions or a C++ component that
  includes `lvgl.h`.

---

## 6. Hardware map (recovered from stock firmware decompilation)

### Display — ✅ driver / ⚠️ pins
- **Type:** circular colour LED touchscreen on the group head; native ESP32-S3
  parallel RGB panel (16-bit). **Best-known resolution: 360×360** (WPM doesn't
  publish exact pixels; the simulator uses this as a placeholder).
- **Stock driver:** `Arduino_ESP32RGBPanel` → `esp_lcd_new_rgb_panel()` (confirmed).
- **ESPHome driver:** **`mipi_rgb`** (`rpi_dpi_rgb` is deprecated as of 2026.6).
- **Framebuffer:** PSRAM-backed.
- ⚠️ **The ~20 RGB data/timing pins** live in the GFX driver constructor, which
  the decompiler couldn't surface cleanly. **Needs a hardware probe.**

### Touch — ✅ fully recovered
- **GT911** capacitive, I²C **SDA=GPIO6, SCL=GPIO5**, dual-address probe 0x5D→0x14.
- ESPHome `gt911` component.

### Sensors & actuators — ✅ roles / ⚠️ exact assignments
From `setup()` decompilation:

| GPIO | Direction | Stock role | ESPHome |
|---|---|---|---|
| 4 | OUTPUT | actuator (pump PWM / solenoid) | `ledc` |
| 19 | OUTPUT | actuator (pump PWM / solenoid) | `ledc` |
| 42 | INPUT_PULLUP | switch / sensor | `binary_sensor` |
| 20 | INPUT | analog/sensor | — |
| 4, 19 | ADC read | pressure baseline / paddle | `adc` |

⚠️ **Needs probe:** pressure-vs-paddle assignment of GPIO4/19, flow-meter input
(likely PCNT), NTC channels.

### Calibration math (binary-compatible with stock NVS) — ✅
```
Pressure(bar) = (ADC - MinPressureADC) * PressureSlope + PresIntercept
              = (ADC - 540) * 0.008 + (-3.0246)         [defaults]
Temperature(°C) = ADC * tempSlope + tempIntercept        [NTC, linear]
```
NVS namespaces: `MachineInfo`, `Calibration`, `Settings`, `UserData`
(`CoffeeProfile` = 7250 B, `HistoryProfile` = 1450 B, `Record` = 95 B/entry).
Full schema in `docs/PRIMUS_RE_RECOVERED.md §C`.

---

## 7. The brew control loop (custom C++ component) — 🚧 deferred

The stock `StateControl` runs a paddle-driven state machine with closed-loop
pressure/flow profiling (modes: Classic 9 Bar, Semi-Manual VPS, Manual VPS, …).

In openPrimus this becomes a `Component` with a FreeRTOS task:

```cpp
class PrimusBrew : public Component {
  void setup() override;             // spawn RTOS task, load calibration
  static void rtos_task(void *);     // ~1 kHz: read ADC → PID → pump LEDC
};
```

**Why it's deferred:** the simulator proves the UI/config/sensor plumbing
without it. On real hardware it's the core piece — and it's also what unlocks
the **live pressure line-chart** (the C++ component can include `lvgl.h` and draw
on a canvas, which YAML lambdas can't do — see §5 note).

PID gains are the one remaining RE unknown (constants inside the stock inner
loop); they need one more targeted decompile or empirical tuning.

---

## 8. What's done vs. what's blocking

| Item | Status |
|---|---|
| Platform choice (ESPHome esp-idf) | ✅ |
| Simulator (SDL2 host) | ✅ runs |
| Touch (GT911) config | ✅ ready |
| Config layer (6 HA numbers) | ✅ |
| Simulated sensors (pressure/temp/flow/weight) | ✅ |
| UI: home, brew, settings pages | ✅ |
| Icons + WiFi topbar | ✅ |
| Pressure gauge (arc) | ✅ |
| On-device settings (sliders) | ✅ |
| Calibration math + NVS schema | ✅ documented |
| Boiler fill algorithm | ✅ ready to port |
| `primus_brew` C++ component (PID loop) | 🚧 deferred |
| Live pressure line-chart | 🚧 deferred (needs C++ component) |
| **RGB display data-pin pinout** | ⚠️ **BLOCKER** for real HW — needs probe |
| Pump-vs-solenoid roles of GPIO4/19 | ⚠️ needs probe |
| Flow-meter input pin | ⚠️ needs probe |
| PID gains | ⚠️ needs decompile or empirical tuning |
| Remaining LVGL screens (19 of 22) | ⚠️ rebuild work |

**Gating item for a first real-hardware boot: the RGB pinout.** Everything else
can proceed via the simulator.

---

## 9. Open questions for hardware probing

1. **RGB panel pinout** — continuity-check each FPC trace to the ESP32-S3 GPIO
   pad, or capture the parallel bus with a logic analyzer.
2. **Pressure vs paddle ADC** — which of GPIO4/GPIO19 is which.
3. **Flow meter** — locate the pulse input (likely PCNT-capable GPIO).
4. **NTC thermistor(s)** — confirm ADC channels + `temp_slope`/`temp_intercept`.
5. **Pump control** — PWM'd vibratory pump vs solenoid on/off?

Each resolves in minutes on a bench.

---

## References
- ESPHome `mipi_rgb` display driver — https://esphome.io/components/display/mipi_rgb/
- ESPHome LVGL cookbook (icons, top_layer) — https://esphome.io/cookbook/lvgl/
- ESPHome 2026.6.0 changelog — https://esphome.io/changelog/2026.6.0/
- ESPHome component architecture (loop timing, RTOS tasks) — https://developers.esphome.io/architecture/components/
- Stock firmware RE details — `docs/PRIMUS_RE_RECOVERED.md`
