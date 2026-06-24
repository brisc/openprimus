// Decompile the setup() function and the display object constructor.
// The Arduino_ESP32RGBPanel constructor is called from setup() with literal pin args.
// Find it by looking at the constructor functions near the GFX class vtable.
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import java.io.*;

public class DecompileSetup extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        FunctionManager fm = p.getFunctionManager();
        ReferenceManager rm = p.getReferenceManager();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(p);
        PrintWriter out = new PrintWriter(outDir + "/setup_display.txt");

        // The display begin-wrapper is FUN_420619b4. Find its callers = setup().
        long beginWrap = 0x420619b4L;
        Address bw = p.getAddressFactory().getDefaultAddressSpace().getAddress(beginWrap);
        out.println("# Callers of display begin-wrapper 0x" + Long.toHexString(beginWrap));
        for (Reference r : rm.getReferencesTo(bw)) {
            Address from = r.getFromAddress();
            Function f = fm.getFunctionContaining(from);
            if (f == null) continue;
            out.printf("  caller 0x%08x in 0x%08x %s%n", from.getOffset(),
                       f.getEntryPoint().getOffset(), f.getName());
        }

        // Decompile setup() focusing on the display-construction part.
        // setup is at 0x4200b9d4 (or 0x42009ec8). Decompile and grep the display section.
        long setupAddr = 0x4200b9d4L;
        Function setup = fm.getFunctionAt(p.getAddressFactory().getDefaultAddressSpace().getAddress(setupAddr));
        if (setup != null) {
            DecompileResults res = decomp.decompileFunction(setup, 180, monitor);
            if (res.decompileCompleted()) {
                String c = res.getDecompiledFunction().getC();
                // Print only the portion around display/panel/gfx creation.
                String[] lines = c.split("\n");
                boolean printing = false;
                int since = 0;
                for (int i = 0; i < lines.length; i++) {
                    String l = lines[i];
                    if (l.contains("0x420619b4") || l.contains("420619") || l.contains("9dc") ||
                        l.toLowerCase().contains("panel") || l.toLowerCase().contains("display") ||
                        l.toLowerCase().contains("rgb")) printing = true;
                    if (printing) {
                        out.println(l);
                        since++;
                        if (since > 80) { printing = false; since = 0; out.println("   ..."); }
                    }
                }
            }
        }
        out.close();
        decomp.dispose();
        println("Wrote setup display section to " + outDir + "/setup_display.txt");
    }
}
