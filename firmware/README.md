# openPrimus firmware

The open-source ESPHome firmware for the WPM Primus (KD360X) espresso machine.

**Status: just starting.** The reverse-engineering is done (see `../docs/`);
the firmware is being built. The simulator runs today; real-hardware bring-up
waits on the display pinout (see [§Status](#status)).

---

## Quick start — run the UI simulator (no hardware needed)

You can design and click-test the entire LVGL UI on your PC before any ESP32
exists. This is the fastest way to iterate and the easiest way to contribute
without owning a Primus.

### 1. Install ESPHome (one-time)

```bash
# from the repo root
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r firmware/requirements.txt
```

SDL2 is required for the simulator window:
- **Linux:** `sudo apt install libsdl2-dev`
- **macOS:** `brew install sdl2`
- **Windows:** use WSL2, then `sudo apt install libsdl2-dev` inside it

### 2. Configure secrets (one-time)

```bash
cp firmware/secrets.yaml.example firmware/secrets.yaml
# edit firmware/secrets.yaml with your WiFi details (used even in sim)
```

### 3. Run the simulator

```bash
esphome run firmware/sim.yaml
```

A window opens showing the Primus UI. Edit `sim.yaml`, re-run, see changes.
The LVGL UI definition is **shared** with the real-hardware config.

---

## Files

| File | Purpose |
|---|---|
| `sim.yaml` | **Simulator** config — SDL2 window on your PC (runs today) |
| `primus.yaml` | **Real hardware** config — ESP32-S3, `mipi_rgb` display (needs pinout) |
| `components/primus_brew/` | Custom component: the real-time brew controller (stub) |
| `requirements.txt` | Pins `esphome==2026.6.2` |
| `secrets.yaml.example` | WiFi credential template (copy to `secrets.yaml`) |

The LVGL UI block is currently duplicated between `sim.yaml` and `primus.yaml`
for clarity. Once the screens are stable it should move to a shared package /
include so the two stay in sync.

---

## Status

| Part | State |
|---|---|
| Simulator UI (SDL2) | ✅ runs today |
| Touch (GT911, SDA=6/SCL=5) | ✅ configured |
| Calibration math / NVS schema | ✅ documented, ready to port |
| Boiler fill algorithm | ✅ documented, ready to port |
| Brew state machine | ✅ documented; `primus_brew` stubbed |
| **RGB display data pins** | ⚠️ **BLOCKER** — needs a hardware probe |
| Pump/solenoid role of GPIO4/19 | ⚠️ needs confirmation |
| Flow-meter input pin | ⚠️ needs confirmation |
| PID gains | ⚠️ needs inner-loop decompile or HW tuning |
| 22 LVGL screens | 🚧 first screen only; rebuild in progress |

**The single gating item for a real-hardware boot is the RGB display pinout.**
Until someone probes the board (continuity check display-FPC → ESP32 GPIO,
~10 min with a multimeter), use `sim.yaml` for all UI work.

See `../docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md §7` for the full gap list.

---

## Contributing

The simulator makes contributing easy — no hardware required:

1. Clone, set up the venv (above), run `sim.yaml`.
2. Pick a screen from the stock UI list
   (`../docs/PRIMUS_FIRMWARE_INTERNALS.md §11`) and rebuild it in LVGL.
3. Open a PR — it's reviewable by running the simulator.

For the brew-control component (`primus_brew`), the design is in
`../docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md §5`. The C++ real-time loop is the
next big piece of work.
