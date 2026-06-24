// Import all WPM Primus firmware segments into a single Ghidra program with
// correct base addresses, so cross-segment (IROM<->DROM) xrefs resolve.
//@author primus-re
//@category Import

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.data.*;
import java.io.*;

public class ImportPrimus extends GhidraScript {

    private void addBlock(Program p, String name, String path, long base, boolean read, boolean write, boolean exe) throws Exception {
        File f = new File(path);
        if (!f.exists()) { println("MISSING: " + path); return; }
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        AddressSpace as = p.getAddressFactory().getDefaultAddressSpace();
        Address start = as.getAddress(base);
        MemoryBlock blk;
        if (exe) {
            blk = p.getMemory().createInitializedBlock(name, start, new ByteArrayInputStream(bytes), bytes.length, null, false);
        } else {
            blk = p.getMemory().createInitializedBlock(name, start, new ByteArrayInputStream(bytes), bytes.length, null, false);
        }
        blk.setRead(read);
        blk.setWrite(write);
        blk.setExecute(exe);
        println(String.format("Added block %-12s 0x%08x len=0x%x (%d bytes) r=%s w=%s x=%s",
                name, base, bytes.length, bytes.length, read, write, exe));
    }

    @Override
    public void run() throws Exception {
        Program p = currentProgram;
        String dir = System.getenv().getOrDefault("SEG_DIR", "/data");
        // (seed block lives at 0x0; it does not overlap any real segment base)
        // DROM: read-only data (strings, rodata, fonts)  -- NOT executable
        addBlock(p, "DROM",  dir + "/seg0_DROM_0x3c110020.bin", 0x3c110020L, true, false, false);
        // DRAM: initialized data
        addBlock(p, "DRAM",  dir + "/seg1_DRAM_0x3fc96990.bin", 0x3fc96990L, true, true,  false);
        // IRAM1: fast code
        addBlock(p, "IRAM1", dir + "/seg2_IRAM_0x40374000.bin", 0x40374000L, true, false, true);
        // IROM: main app code
        addBlock(p, "IROM",  dir + "/seg3_IROM_0x42000020.bin", 0x42000020L, true, false, true);
        // IRAM2: fast code (entry point)
        addBlock(p, "IRAM2", dir + "/seg4_IRAM_0x40376988.bin", 0x40376988L, true, false, true);
        println("All blocks added. Now auto-analyze via the runner.");
    }
}
