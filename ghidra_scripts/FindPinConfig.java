// Find references to the Arduino_ESP32RGBPanel::begin() function and dump
// the surrounding code that builds the esp_lcd_rgb_panel_config_t (the pin map).
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import java.io.*;

public class FindPinConfig extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        ReferenceManager rm = p.getReferenceManager();
        FunctionManager fm = p.getFunctionManager();

        // begin() target
        long beginAddr = 0x420610fcL;
        Address begin = p.getAddressFactory().getDefaultAddressSpace().getAddress(beginAddr);

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(p);
        PrintWriter out = new PrintWriter(outDir + "/pinconfig.txt");

        out.println("# References to Arduino_ESP32RGBPanel::begin() @ 0x" + Long.toHexString(beginAddr));
        int n = 0;
        for (Reference r : rm.getReferencesTo(begin)) {
            Address from = r.getFromAddress();
            Function f = fm.getFunctionContaining(from);
            if (f == null) continue;
            out.printf("  caller 0x%08x in func 0x%08x %s%n", from.getOffset(),
                       f.getEntryPoint().getOffset(), f.getName());
            // Decompile that caller so we can read the pin config writes
            DecompileResults res = decomp.decompileFunction(f, 120, monitor);
            if (res.decompileCompleted()) {
                out.println("  === caller decompilation ===");
                out.println(res.getDecompiledFunction().getC());
            }
            n++;
        }
        out.println("# total callers: " + n);
        out.close();
        decomp.dispose();
        println("Wrote " + n + " pin-config callers to " + outDir + "/pinconfig.txt");
    }
}
