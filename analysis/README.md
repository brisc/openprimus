# Analysis Artifacts

This directory holds **generated** artifacts produced by the analysis pipeline.
Most of it is gitignored (regeneratable) — only this README and the reproducible
toolchain under `../scripts/` are committed.

## Layout

```
analysis/
├── README.md                  ← this file
└── extracted/                 ← (gitignored) extracted firmware segments
    ├── seg0_DROM_0x3c110020.bin   2.65 MB  read-only data (strings, assets)
    ├── seg1_DRAM_0x3fc96990.bin   24 KB    initialized data (.data)
    ├── seg2_IRAM_0x40374000.bin   10 KB    fast IRAM code
    ├── seg3_IROM_0x42000020.bin   1.08 MB  main application code
    └── seg4_IRAM_0x40376988.bin   64 KB    fast IRAM code (entry point)
```

The `strings_seg*.txt` dumps are also generated here (gitignored).

## How to regenerate everything

From the repo root:

```bash
# 1. get the firmware (into firmware/) — pulled from the public OTA endpoint
./scripts/fetch_firmware.sh           # default region: DE

# 2. extract the 5 segments (into analysis/extracted/)
python3 scripts/extract_segments.py

# 3. run the full Ghidra decompilation pipeline (into ghidra_out/)
./scripts/run_ghidra.sh
```

See `../docs/` for the written-up findings derived from these artifacts.
