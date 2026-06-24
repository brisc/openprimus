#!/usr/bin/env python3
"""Map each string's code-xref to its containing Ghidra function -> name functions."""
import sys, bisect

# Load functions: list of (start_addr, end_addr, name)
funcs = []
with open('ghidra_out/functions.txt') as f:
    for line in f:
        line=line.strip()
        if not line or line.startswith('#'): continue
        parts = line.split()
        addr = int(parts[0],16); name = parts[1]; size = int(parts[2])
        funcs.append((addr, addr+size, name))
funcs.sort()
starts = [f[0] for f in funcs]

def func_of(addr):
    i = bisect.bisect_right(starts, addr) - 1
    if i >= 0 and funcs[i][0] <= addr < funcs[i][1]:
        return funcs[i][2], funcs[i][0]
    return None, None

# Parse strings_xrefs: build map code_addr -> (string, str_addr)
code_to_str = {}
with open('ghidra_out/strings_xrefs.txt') as f:
    for line in f:
        line=line.rstrip('\n')
        if not line or line.startswith('#'): continue
        # format: 0xADDR BLOCK string | 0xCODE 0xCODE ...
        try:
            head, xrefs = line.split(' | ',1)
            parts = head.split(None,2)
            s = parts[2] if len(parts)>2 else ''
            for tok in xrefs.split():
                ca = int(tok,16)
                code_to_str.setdefault(ca, (s, parts[0]))
        except: pass

# For each function, collect representative strings it references
func_strings = {}
for ca, (s, sa) in code_to_str.items():
    fn, fstart = func_of(ca)
    if fn:
        func_strings.setdefault(fn, []).append((ca, s))

# Pick a "name" for each function based on most informative string it uses
# Priority: __FILE__ cpp names, then distinctive log strings
out = open('ghidra_out/function_names.txt','w')
named = 0
for fstart, fend, name in funcs:
    strs = func_strings.get(name, [])
    if not strs:
        continue
    cpp = [s for ca,s in strs if s.endswith('.cpp')]
    chosen = None
    if cpp:
        # use the cpp filename - this is the source file of the function
        chosen = 'fn_' + cpp[0].split('/')[-1].replace('.cpp','')
    else:
        # use a distinctive string
        for ca,s in strs:
            if any(k in s.lower() for k in ['god','boiler','brew','paddle','pressure','ota','wifi','profile','vps']):
                chosen = 'fn_' + s.split()[0].split('(')[0][:25].replace('%','').replace(':','').replace(' ','_').lower()
                break
    if chosen:
        out.write(f"0x{fstart:08x} {name} {chosen}  <- {cpp[0] if cpp else strs[0][1][:50]}\n")
        named += 1
out.close()
print(f"Named {named} of {len(funcs)} functions")
