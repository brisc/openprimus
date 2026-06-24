#!/usr/bin/env python3
"""
Xtensa firmware analyzer: string -> code cross-referencing.

On Xtensa LX7, strings in DROM/IROM are loaded via `l32r Rx, offset` which
loads a 32-bit pointer from a "literal pool" (a region of .rodata holding
addresses). To find code referencing a string at address S:
  1. Find all 32-bit words in DROM that equal S  -> these are literal-pool slots
  2. For each literal-pool slot at address L, find `l32r` instrs whose target = L
"""
import sys, struct, re, bisect
from collections import defaultdict

DROM_BASE = 0x3c110020
IROM_BASE = 0x42000020
IRAM2_BASE = 0x40376988  # seg4
DRAM_BASE  = 0x3fc96990  # seg1

with open('extracted/seg0_DROM_0x3c110020.bin','rb') as f: DROM = f.read()
with open('extracted/seg3_IROM_0x42000020.bin','rb') as f: IROM = f.read()
with open('extracted/seg4_IRAM_0x40376988.bin','rb') as f: IRAM2 = f.read()
with open('extracted/seg1_DRAM_0x3fc96990.bin','rb') as f: DRAM = f.read()

# Map: any virtual address -> bytes
def vmem(addr, n):
    if DROM_BASE <= addr < DROM_BASE+len(DROM):  return DROM[addr-DROM_BASE:addr-DROM_BASE+n]
    if IROM_BASE <= addr < IROM_BASE+len(IROM):  return IROM[addr-IROM_BASE:addr-IROM_BASE+n]
    if IRAM2_BASE <= addr < IRAM2_BASE+len(IRAM2): return IRAM2[addr-IRAM2_BASE:addr-IRAM2_BASE+n]
    if DRAM_BASE <= addr < DRAM_BASE+len(DRAM):  return DRAM[addr-DRAM_BASE:addr-DRAM_BASE+n]
    return None

def read_u32(addr):
    b = vmem(addr,4)
    return struct.unpack('<I', b)[0] if b else None

# ---- Build literal-pool index: for each address value, where it appears as a 4-byte word ----
# This lets us find pointers TO a given address anywhere in DROM/IROM/IRAM.
print("Building pointer index...", file=sys.stderr)
ptr_index = defaultdict(list)  # pointed_value -> [addresses where it appears as a word]
for base, data, name in [(DROM_BASE,DROM,'DROM'),(IROM_BASE,IROM,'IROM'),(IRAM2_BASE,IRAM2,'IRAM2')]:
    for off in range(0, len(data)-3, 4):
        val = struct.unpack_from('<I', data, off)[0]
        if 0x3c000000 <= val <= 0x7fffffff:  # plausible code/rodata pointer
            ptr_index[val].append(base+off)

# ---- l32r target computation ----
# l32r aT, [base-reg] : base reg is a0's high bits from LITBASE register normally,
# but in ESP-IDF/windowed it uses the implicit base = (PC & ~0x3) + 4 + (signext(imm8)<<2)? NO.
# Real l32r: addr = (PC & 0xFFFFFFFC) - (sign_extend(imm8) << 2)  ... actually:
# l32r loads from:  BASE = SAR? No. The base is set by the LITBASE special register if LITBASE option,
# otherwise relative to current PC: target = ( (PC) & ~3 ) + (signext_8(imm)<<2) is for MOVI/L32R?
# Correct (no LITBASE):  target = ( (addr_of_l32r_instr) & ~3 ) - (imm8 << 2) ... but imm8 is treated
# as a negative 16-bit? The encoding: l32r aT has 8-bit immediate n; target = (PC & ~3) - (n<<2).
# Wait, Xtensa l32r:  VA = ( (PC) & 0xFFFFFFFC ) + SignExtend(imm8) * 4  where imm8 sign-extended? No.
# Per ISA: l32r loads from  address = ( (PC & ~3) + (-imm8 << 2) ) i.e. backward, OR with LITBASE.
# Capstone gives us the resolved target in op_str (". 0xADDR"), so we don't compute it ourselves.
from capstone import *
md = Cs(CS_ARCH_XTENSA, 0)

def disasm_iter():
    """Yield (ins_address, mnemonic, op_str, raw) for all code segments."""
    for base, data, name in [(IROM_BASE,IROM,'IROM'),(IRAM2_BASE,IRAM2,'IRAM2')]:
        for ins in md.disasm(data, base):
            yield ins

# Index every l32r by its resolved target
print("Indexing l32r instructions...", file=sys.stderr)
l32r_by_target = defaultdict(list)  # literal_address -> [code addresses]
all_callsites = defaultdict(list)
func_starts = set()
for ins in md.disasm(IROM, IROM_BASE):
    if ins.mnemonic == 'l32r' and '.' in ins.op_str:
        try:
            tgt = int(ins.op_str.split('.')[-1].strip(), 16)
            l32r_by_target[tgt].append(ins.address)
        except: pass
    if ins.mnemonic == 'call8' and '.' in ins.op_str:
        try:
            tgt = int(ins.op_str.split('.')[-1].strip(), 16)
            all_callsites[tgt].append(ins.address)
        except: pass
for ins in md.disasm(IRAM2, IRAM2_BASE):
    if ins.mnemonic == 'l32r' and '.' in ins.op_str:
        try:
            tgt = int(ins.op_str.split('.')[-1].strip(), 16)
            l32r_by_target[tgt].append(ins.address)
        except: pass

print(f"Indexed {sum(len(v) for v in l32r_by_target.values())} l32r refs, {len(all_callsites)} call8 sites", file=sys.stderr)

def find_string_xrefs(substr):
    """Find a string containing substr in DROM, then code that references it."""
    needle = substr.encode() if isinstance(substr,str) else substr
    results = []
    idx = 0
    while True:
        p = DROM.find(needle, idx)
        if p < 0: break
        str_addr = DROM_BASE + p
        # find literal-pool slots pointing to str_addr
        slots = ptr_index.get(str_addr, [])
        code_refs = []
        for slot in slots:
            code_refs.extend(l32r_by_target.get(slot, []))
        # read the actual string
        end = DROM.find(b'\x00', p)
        s = DROM[p:end].decode('latin1') if end>=0 else '?'
        results.append((str_addr, s, code_refs))
        idx = p + 1
    return results

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: xref.py '<substring>'   OR   xref.py --calls <hexaddr>")
        sys.exit(1)
    if sys.argv[1] == '--calls':
        addr = int(sys.argv[2],16)
        print(f"Callers of 0x{addr:x}: {[hex(a) for a in all_callsites.get(addr,[])]}")
    elif sys.argv[1] == '--string2code':
        for a,s,refs in find_string_xrefs(sys.argv[2]):
            print(f"0x{a:08x}  {s!r}")
            for r in refs[:10]:
                print(f"        <- code 0x{r:08x}")
    else:
        for a,s,refs in find_string_xrefs(sys.argv[1]):
            print(f"0x{a:08x}  {s!r}   refs: {[hex(r) for r in refs[:6]]}")
