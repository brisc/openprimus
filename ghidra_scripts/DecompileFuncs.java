// Decompile specific functions by address for the WPM Primus firmware.
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import java.io.*;

public class DecompileFuncs extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        String addrList = System.getenv().getOrDefault("ADDRS", "");
        if (addrList.isEmpty()) { println("ADDRS env not set"); return; }

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        decomp.setSimplificationStyle("decompile");

        FunctionManager fm = currentProgram.getFunctionManager();
        PrintWriter all = new PrintWriter(outDir + "/decompiled.c");
        for (String tok : addrList.split(",")) {
            long a = Long.parseLong(tok.trim(), 16);
            Address addr = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a);
            Function f = fm.getFunctionContaining(addr);
            if (f == null) {
                println("No function at 0x" + Long.toHexString(a));
                continue;
            }
            DecompileResults res = decomp.decompileFunction(f, 120, monitor);
            if (res.decompileCompleted()) {
                String code = res.getDecompiledFunction().getC();
                all.println("// ====== Function @ 0x" + Long.toHexString(f.getEntryPoint().getOffset()) + " ======");
                all.println(code);
                all.println();
                println("Decompiled function at 0x" + Long.toHexString(f.getEntryPoint().getOffset()) + " (" + code.length() + " chars)");
            } else {
                all.println("// FAILED to decompile 0x" + Long.toHexString(a) + ": " + res.getErrorMessage());
                println("Failed: " + res.getErrorMessage());
            }
        }
        all.close();
        decomp.dispose();
        println("Decompilation written to " + outDir + "/decompiled.c");
    }
}
