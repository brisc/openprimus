// Brute-force: find ALL functions that reference ANY display/GFX-related string or address,
// then decompile the smallest ones (constructors are small). Also dump any data table near
// the GFX object that contains resolution/timing/pin values.
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import java.io.*;
import java.util.*;

public class FindAllDisplayRefs extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        FunctionManager fm = p.getFunctionManager();
        ReferenceManager rm = p.getReferenceManager();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(p);
        PrintWriter out = new PrintWriter(outDir + "/all_display.txt");

        // Collect every function that references a display-ish DROM string.
        // Display strings cluster in 0x3c111e00..0x3c112000 and 0x3c36a000..0x3c36a200.
        Set<Long> hitFuncs = new TreeSet<>();
        AddressSpace as = p.getAddressFactory().getDefaultAddressSpace();
        long[][] ranges = {{0x3c111e00L,0x3c112200L},{0x3c36a000L,0x3c36a200L}};
        for (long[] r : ranges) {
            Address cur = as.getAddress(r[0]);
            Address end = as.getAddress(r[1]);
            while (cur.compareTo(end) < 0) {
                for (Reference ref : rm.getReferencesTo(cur)) {
                    Function f = fm.getFunctionContaining(ref.getFromAddress());
                    if (f != null) hitFuncs.add(f.getEntryPoint().getOffset());
                }
                cur = cur.add(1);
            }
        }
        out.println("# Functions referencing display strings: " + hitFuncs.size());
        for (long ep : hitFuncs) {
            Function f = fm.getFunctionAt(as.getAddress(ep));
            out.printf("  0x%08x %s size=%d%n", ep, f.getName(), f.getBody().getNumAddresses());
        }

        // Decompile the 3 smallest (most likely ctor) + any with "RGBPanel"
        List<long[]> sized = new ArrayList<>();
        for (long ep : hitFuncs) {
            Function f = fm.getFunctionAt(as.getAddress(ep));
            sized.add(new long[]{ep, f.getBody().getNumAddresses()});
        }
        sized.sort((a,b)->Long.compare(a[1],b[1]));
        int dumped = 0;
        for (long[] e : sized) {
            if (dumped >= 8 && e[1] > 2000) break;
            Function f = fm.getFunctionAt(as.getAddress(e[0]));
            DecompileResults res = decomp.decompileFunction(f, 90, monitor);
            if (res.decompileCompleted()) {
                String c = res.getDecompiledFunction().getC();
                out.println("\n=== 0x" + Long.toHexString(e[0]) + " " + f.getName() +
                            " size=" + e[1] + " ===");
                out.println(c);
                dumped++;
            }
        }
        out.close();
        decomp.dispose();
        println("Wrote " + dumped + " display functions to " + outDir + "/all_display.txt");
    }
}
