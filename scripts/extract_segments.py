#!/usr/bin/env python3
"""
Extract the segments from a WPM Primus firmware .bin into analysis/extracted/.

Mirrors the layout that esptool reports:
  seg0 DROM  0x3c110020   (strings, assets)
  seg1 DRAM  0x3fc96990   (initialized data)
  seg2 IRAM  0x40374000   (fast code)
  seg3 IROM  0x42000020   (main app code)
  seg4 IRAM  0x40376988   (fast code, entry point)

Usage:  python3 scripts/extract_segments.py firmware/KD360X_OTA_*.bin
        python3 scripts/extract_segments.py   # auto-finds the firmware
"""
import os, sys, struct

def main():
    if len(sys.argv) > 1:
        bin_path = sys.argv[1]
    else:
        import glob
        matches = glob.glob("firmware/*.bin")
        if not matches:
            sys.exit("No firmware found. Run scripts/fetch_firmware.sh first, "
                     "or pass the .bin path as an argument.")
        bin_path = sorted(matches)[-1]

    if not os.path.isfile(bin_path):
        sys.exit(f"Not found: {bin_path}")

    with open(bin_path, "rb") as f:
        data = f.read()

    if data[0] != 0xE9:
        sys.exit("Not an ESP-IDF application image (bad magic)")

    seg_count = data[1]
    print(f"{bin_path}: {len(data)} bytes, {seg_count} segments")

    header_size = 24  # S3 extended header
    out_dir = os.path.join("analysis", "extracted")
    os.makedirs(out_dir, exist_ok=True)

    # Print the image header fields (cheap version of esptool image_info)
    entry = struct.unpack_from("<I", data, 4)[0]
    print(f"  Entry point: 0x{entry:08x}")

    off = header_size
    names = {0: "DROM", 1: "DRAM", 2: "IRAM1", 3: "IROM", 4: "IRAM2"}
    for i in range(seg_count):
        load_addr = struct.unpack_from("<I", data, off)[0]
        length = struct.unpack_from("<I", data, off + 4)[0]
        seg = data[off + 8 : off + 8 + length]
        kind = names.get(i, f"SEG{i}")
        fname = f"seg{i}_{kind}_0x{load_addr:08x}.bin"
        with open(os.path.join(out_dir, fname), "wb") as out:
            out.write(seg)
        print(f"  seg{i} {kind:6} addr=0x{load_addr:08x} len={length:7d} -> {fname}")
        off += 8 + length

    print(f"\nExtracted {seg_count} segments to {out_dir}/")

if __name__ == "__main__":
    main()
