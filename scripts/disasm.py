#!/usr/bin/env python3
"""Xtensa LX7 disassembler for ESP32-S3 firmware segments."""
import sys, struct
from capstone import *

def load_seg(path, base):
    with open(path, 'rb') as f:
        return base, f.read()

# Segment load addresses
SEGMENTS = {
    'IROM': ('extracted/seg3_IROM_0x42000020.bin', 0x42000020),
    'IRAM2': ('extracted/seg4_IRAM_0x40376988.bin', 0x40376988),
    'IRAM1': ('extracted/seg2_IRAM_0x40374000.bin', 0x40374000),
}

_XTENSA_MODE_LE = 0  # Xtensa little-endian default mode
md = Cs(CS_ARCH_XTENSA, _XTENSA_MODE_LE)
md.detail = True

def disasm_range(seg_name, addr, count=40):
    path, base = SEGMENTS[seg_name]
    with open(path,'rb') as f: data = f.read()
    off = addr - base
    if off < 0 or off >= len(data):
        print(f"addr 0x{addr:x} out of range for {seg_name} (base 0x{base:x})")
        return
    print(f"===== {seg_name} @ 0x{addr:08x} =====")
    for ins in md.disasm(data[off:off+count*4], addr):
        print(f"  0x{ins.address:08x}:  {ins.bytes.hex():<10} {ins.mnemonic:<8} {ins.op_str}")
        count -= 1
        if count <= 0: break

if __name__ == '__main__':
    if len(sys.argv) >= 3:
        seg = sys.argv[1]; addr = int(sys.argv[2], 16)
        n = int(sys.argv[3]) if len(sys.argv) > 3 else 40
        disasm_range(seg, addr, n)
    else:
        print("Usage: disasm.py <SEG> <hexaddr> [count]")
        print("Segments: " + ", ".join(SEGMENTS))
