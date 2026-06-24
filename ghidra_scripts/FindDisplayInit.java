// Find the function that calls gfx->begin() (references the "gfx->begin() failed!" string's
// code site) and decompile it - this is where the display object + panel config are built.
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import java.io.*;

public class FindDisplayInit extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        FunctionManager fm = p.getFunctionManager();
        ReferenceManager rm = p.getReferenceManager();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(p);
        PrintWriter out = new PrintWriter(outDir + "/display_init.txt");

        // The "gfx->begin() failed!" string is at 0x3c111f51. Find code refs to it.
        long strAddr = 0x3c111f51L;
        Address sa = p.getAddressFactory().getDefaultAddressSpace().getAddress(strAddr);
        out.println("# Code referencing \"gfx->begin() failed!\" @ 0x" + Long.toHexString(strAddr));
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (Reference r : rm.getReferencesTo(sa)) {
            Address from = r.getFromAddress();
            Function f = fm.getFunctionContaining(from);
            if (f == null) continue;
            long ep = f.getEntryPoint().getOffset();
            out.printf("  ref 0x%08x in func 0x%08x %s (size %d)%n", from.getOffset(),
                       ep, f.getName(), f.getBody().getNumAddresses());
            if (done.add(ep)) {
                // Decompile the WHOLE function
                DecompileResults res = decomp.decompileFunction(f, 180, monitor);
                if (res.decompileCompleted()) {
                    out.println("  === FULL decompile ===");
                    out.println(res.getDecompiledFunction().getC());
                }
            }
        }
        out.close();
        decomp.dispose();
        println("Wrote display init to " + outDir + "/display_init.txt");
    }
}
