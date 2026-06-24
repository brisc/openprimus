// Find the Arduino_ESP32RGBPanel constructor (the function that writes ~20 GPIO pin
// fields into the panel object) by locating references to the GFX class strings and
// decompiling nearby functions. Also dumps any function whose decompile body contains
// many small constant stores (the pin assignments).
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import java.io.*;

public class FindRgbCtor extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        FunctionManager fm = p.getFunctionManager();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(p);
        PrintWriter out = new PrintWriter(outDir + "/rgb_ctor_candidates.txt");

        // The panel object is created in setup. Find functions that reference the
        // "Arduino_ESP32RGBPanel.cpp" source string AND are small (constructors).
        // Then decompile the few functions immediately before begin()'s caller region.
        // Strategy: decompile every function in 0x42060000..0x42062000 (the GFX lib code block)
        // and report those that contain many movi/small-int stores to a struct.
        long lo = 0x42060000L, hi = 0x42062500L;
        AddressSpace as = p.getAddressFactory().getDefaultAddressSpace();
        out.println("# Functions in GFX lib region 0x" + Long.toHexString(lo) + "..0x" + Long.toHexString(hi));
        int n = 0;
        FunctionIterator it = fm.getFunctions(as.getAddress(lo), true);
        while (it.hasNext()) {
            Function f = it.next();
            long ep = f.getEntryPoint().getOffset();
            if (ep > hi) break;
            if (ep < lo) continue;
            DecompileResults res = decomp.decompileFunction(f, 60, monitor);
            if (!res.decompileCompleted()) continue;
            String c = res.getDecompiledFunction().getC();
            // Heuristic: a constructor stores many small constants. Count "= <small int>" stores.
            int smallStores = 0;
            for (String line : c.split("\n")) {
                String t = line.trim();
                if (t.matches(".*\\) ?= (\\d{1,2});.*") || t.matches(".*\\[.*\\] = (\\d{1,2});.*")) {
                    smallStores++;
                }
            }
            if (smallStores >= 8 || c.contains("RGBPanel") || (c.length() < 4000 && smallStores >= 5)) {
                out.println("=== candidate 0x" + Long.toHexString(ep) + " " + f.getName() +
                            " smallStores=" + smallStores + " len=" + c.length() + " ===");
                out.println(c);
                out.println();
                n++;
            }
        }
        out.println("# candidates: " + n);
        out.close();
        decomp.dispose();
        println("Wrote " + n + " RGB-ctor candidates to " + outDir + "/rgb_ctor_candidates.txt");
    }
}
