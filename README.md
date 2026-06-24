# WPM Primus (KD360X) — Firmware Reverse-Engineering & Open-Source Firmware

Reverse-engineering documentation and tooling for the **WPM Primus** espresso
machine firmware (model **KD360X**, an ESP32-S3 based lever/paddle machine with
pressure & flow profiling), plus an architecture for an **open-source
replacement firmware** built on ESPHome.

The goal of this project is to **fully document how the stock firmware works**
and build the tools to understand it — for repairability, customization, and
ownership of the hardware you bought. The end product is **`primus-os`**, an
open alternative firmware you can flash to the ESP32.

> ⚖️ **Scope & intent:** This is an interoperability / security-research project.
> It analyzes a device the author owns, documents its behavior, and builds tools
> to understand it. It does **not** redistribute proprietary firmware, bypass DRM,
> or enable unauthorized access to others' devices. See
> [Legal & Ethics](#-legal--ethics).

---

## 📖 Documentation

All findings are written up in `docs/` — read in this order:

| Doc | What it covers |
|---|---|
| [`docs/PHASE1_OVERVIEW.md`](docs/PHASE1_OVERVIEW.md) | High-level architecture & feature inventory (strings-based analysis) |
| [`docs/PRIMUS_FIRMWARE_INTERNALS.md`](docs/PRIMUS_FIRMWARE_INTERNALS.md) | Full subsystem documentation: platform, memory map, OTA, NVS, UI, state machine |
| [`docs/PRIMUS_RE_RECOVERED.md`](docs/PRIMUS_RE_RECOVERED.md) | Ghidra decompilation findings: pin map, calibration constants, boiler algorithm, GodDoor |
| [`docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md`](docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md) | The open-firmware design — ESPHome 2026.6 + custom C++ component |

---

## 📋 Summary of findings

| Subsystem | Status | Highlights |
|---|---|---|
| **Platform** | ✅ | ESP32-S3, Arduino-ESP32 2.0.14 / IDF 4.4.6, LVGL UI, 16 MB flash (DIO/80 MHz) |
| **Display** | ✅ driver / ⚠️ pins | Native ESP32-S3 RGB LCD panel (parallel RGB); data pins need HW probe |
| **Touch** | ✅ | GT911 over I²C (SDA=GPIO6, SCL=GPIO5), dual-address probe 0x5D/0x14 |
| **Sensors** | ✅ | Pressure transducer + paddle on ADC (GPIO4/19), NTC thermistors, flow meter |
| **Calibration** | ✅ | Linear regression (`slope/intercept`); defaults fully recovered |
| **Brew control** | ✅ | "VPS" pressure/flow profiling, 7+ modes, paddle-driven state machine |
| **Boiler mgmt** | ✅ | Fill algorithm with debounce + volume + timeout interlocks |
| **NVS storage** | ✅ | Schema + defaults documented (incl. factory test WiFi creds) |
| **OTA updates** | ✅ | Azure Blob + Front Door CDN, region-locked CSV manifest |
| **WiFi onboarding** | ✅ | SoftAP captive portal (no BLE, no cloud telemetry) |
| **Service menu** | ✅ | "GodDoor" engineering menu exists (architecture documented; unlock sequence intentionally not published) |
| **RGB panel pins** | ⚠️ | In GFX driver ctor; board-specific, needs HW probe |
| **PID gains** | ⚠️ | Control loop located; coefficients need inner-loop decompile |

Recovered calibration defaults (from NVS init):
```
Pressure(bar) = (ADC - 540) * 0.008 + (-3.0246)
MinPaddle = 2000   MaxPaddle = 4000
CoffeeProfile blob = 7250 bytes
```

---

## 🛠 Tech stack of the stock firmware

- **SoC:** ESP32-S3 (Xtensa LX7 dual-core)
- **Framework:** Arduino-ESP32 2.0.14 on ESP-IDF 4.4.6, built with PlatformIO (dev: `vincent_dev`)
- **UI:** LVGL (touch, bilingual EN/DE, 22 screens)
- **Display driver:** Arduino_GFX `Arduino_ESP32RGBPanel` → native `esp_lcd_rgb_panel`
- **TLS:** mbedTLS with embedded CA bundle (for OTA only)
- **Networking:** WiFi STA + SoftAP captive portal; **no MQTT, no cloud telemetry**

Notably the device carries **no embedded secrets** — clean OTA-over-HTTPS design,
though secure boot & flash encryption are off (which is why this analysis is
possible at all).

---

## 🔁 Reproducing the analysis

Everything is reproducible from scratch with free tools — no proprietary data
shipped.

### Prerequisites
- **Docker** (for Ghidra — no local install needed)
- Python 3.10+ with `esptool` and `capstone`:
  ```bash
  pip install esptool capstone   # use capstone>=6.0.0a* for Xtensa support
  ```

### Steps
```bash
# 1. Fetch the firmware from the device's own public OTA endpoint
./scripts/fetch_firmware.sh           # default region: DE
#    → saves firmware_binary/KD360X_OTA_1.1.54_DE_20260623.bin

# 2. Extract the 5 memory segments
python3 scripts/extract_segments.py
#    → analysis/extracted/seg0..4_*.bin

# 3. Run the full Ghidra decompilation pipeline (Docker, ~90s)
./scripts/run_ghidra.sh
#    → ghidra_out/{functions,strings_xrefs,data_xrefs,function_names}.txt
#      ghidra_out/decompiled.c
```

Individual tools (optional, for targeted queries):
```bash
# disassemble a range of Xtensa code
python3 scripts/disasm.py IROM 0x4200891c 40

# find code referencing a string
python3 scripts/xref.py "Start Make Coffee"
```

---

## 🏗 The open-source firmware (`primus-os`)

Rather than cloning the stock Arduino/IDF approach, the open firmware is built on
**ESPHome 2026.6** with one custom C++ component for the real-time brew control
loop. ESPHome gives us, for free, the ~90 % of the firmware that is plumbing
(display, touch, WiFi, OTA, sensor drivers); only the brew control loop needs
hand-written real-time C++.

**The simulator runs today** — you can build and click-test the LVGL UI on your
PC with no ESP32 hardware. This is the fastest way to contribute UI work before
the display pinout is confirmed on real hardware:

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r firmware/requirements.txt
cp firmware/secrets.yaml.example firmware/secrets.yaml   # edit WiFi creds
esphome run firmware/sim.yaml        # → a window opens with the UI
```

(Linux: `sudo apt install libsdl2-dev` · macOS: `brew install sdl2` · Windows: use WSL2)

**Key decisions** (full rationale in
[`docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md`](docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md)):

- Framework: **ESP-IDF** (parallel RGB + PSRAM require it; `arduino` won't work)
- Display driver: **`mipi_rgb`** (note: `rpi_dpi_rgb` is deprecated as of ESPHome 2026.6)
- Touch: native **`gt911`** component
- Brew loop: a **FreeRTOS task** in a custom `primus_brew` component (tighter than ESPHome's ~7–16 ms main loop)
- NVS: mirrors the stock schema so a flashed `primus-os` reads the unit's factory calibration

Layout (`firmware/`):
```
firmware/
├── sim.yaml                       ← SDL2 simulator — runs the UI on your PC today
├── primus.yaml                    ← real-hardware config (display pins = ⚠️ TODO)
├── components/
│   └── primus_brew/               ← custom component: the real-time brew controller
│       └── __init__.py             YAML schema + config validation (C++ stub next)
├── requirements.txt                pins esphome==2026.6.2
└── secrets.yaml.example            WiFi credential template
```
See [`firmware/README.md`](firmware/README.md) for the full setup and status.

---

## 📈 Status & roadmap

**Done:** full platform/architecture documentation, OTA protocol, NVS schema,
calibration math, boiler algorithm, brew state-machine structure, hardware pin
map (partial), Ghidra decompilation pipeline, the ESPHome open-firmware
architecture, and a **runnable UI simulator** (`firmware/sim.yaml`).

**Next:**
- [ ] Recover the RGB-panel data pin map (HW probe — logic analyzer / silkscreen)
- [ ] Decompile the StateControl inner control loop → extract PID coefficients
- [ ] Confirm remaining sensor/actuator roles on real hardware (pressure vs paddle ADC, flow meter, GPIO4/19 pump-vs-solenoid)
- [ ] Rebuild the 22 LVGL screens (contributor-friendly via the simulator — no hardware needed)
- [ ] Implement the `primus_brew` C++ real-time control loop (pressure PID + VPS profiling)

Contributions welcome — see
[`docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md §7`](docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md)
for the exact gating items.

---

## ⚖️ Legal & Ethics

- The WPM Primus firmware is **proprietary and copyrighted** by its manufacturer.
  This repo does **not** redistribute it. Use `scripts/fetch_firmware.sh` to pull
  it from the same public, unauthenticated OTA endpoint the device uses.
- This is a **clean-room-style interoperability** effort: we observe behavior and
  document it for the purpose of building compatible open software. No leaked
  source code is used.
- The OTA endpoint queried here is the device's own **public firmware-distribution
  server** (no credentials, no device-specific data — just published firmware
  files), equivalent to any vendor's public download page.
- Reverse engineering for interoperability is broadly protected (US: *Sega v.
  Accolade*; EU: Software Directive 2009/24/EC). This device has **secure boot
  and flash encryption off**, so no technological protection measure is
  circumvented. This is not legal advice — consult a lawyer before a public launch.
- This repo **deliberately does not publish** operational secrets that could
  enable unauthorized access to others' devices — e.g. the service-menu unlock
  sequence and the hardcoded factory-programming WiFi credentials. Documenting
  that these exist (architectural facts) is fine; publishing ready-to-use bypass
  material is not. Such items remain recoverable from the firmware by anyone with
  a legitimate repair need.
- If you are the rightsholder and have concerns, please open an issue.

---

## 📄 License

The **documentation and tooling** in this repo (Markdown, Python, shell, Java
scripts) are released under the **MIT License**. See `LICENSE`.

The firmware they analyze remains the property of its manufacturer.
