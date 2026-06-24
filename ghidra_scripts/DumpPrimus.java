// Ghidra headless script: dump analysis results for the WPM Primus firmware
//@author primus-re
//@category Analysis

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.data.*;
import java.io.*;

public class DumpPrimus extends GhidraScript {

    private int countRefsTo(ReferenceManager rm, Address a) {
        int n = 0;
        ReferenceIterator it = rm.getReferencesTo(a);
        while (it.hasNext()) { it.next(); n++; }
        return n;
    }

    @Override
    public void run() throws Exception {
        String outDir = System.getenv().getOrDefault("OUT_DIR", "/tmp/ghidra_out");
        new File(outDir).mkdirs();
        Program p = currentProgram;
        ReferenceManager rm = p.getReferenceManager();
        println("Dumping to " + outDir);

        // 1) Function list with sizes
        PrintWriter out = new PrintWriter(outDir + "/functions.txt");
        out.println("# addr name size");
        FunctionManager fm = p.getFunctionManager();
        int fcount = 0;
        for (Function f : fm.getFunctions(true)) {
            out.printf("0x%08x %s %d%n", f.getEntryPoint().getOffset(),
                       f.getName(), f.getBody().getNumAddresses());
            fcount++;
        }
        out.close();
        println("Functions dumped: " + fcount);

        // 2) Strings with xrefs (code addresses referencing each string)
        out = new PrintWriter(outDir + "/strings_xrefs.txt");
        DataIterator di = p.getListing().getDefinedData(true);
        int scount = 0;
        while (di.hasNext()) {
            Data d = di.next();
            if (!d.hasStringValue()) continue;
            scount++;
            StringBuilder refs = new StringBuilder();
            ReferenceIterator it = rm.getReferencesTo(d.getAddress());
            while (it.hasNext()) {
                Reference r = it.next();
                if (r.getReferenceType().isData()) {
                    refs.append(String.format(" 0x%08x", r.getFromAddress().getOffset()));
                }
            }
            if (refs.length() > 0) {
                String s = (String) d.getValue();
                s = s.replace('\n', ' ').replace('\r', ' ');
                if (s.length() > 120) s = s.substring(0, 120);
                String bname = "??";
                MemoryBlock blk = p.getMemory().getBlock(d.getAddress());
                if (blk != null) bname = blk.getName();
                out.printf("0x%08x %-12s %s |%s%n", d.getAddress().getOffset(), bname, s, refs);
            }
        }
        out.close();
        println("Strings with xrefs dumped: " + scount);

        // 3) Non-string defined data with code xrefs (literal constants / pin numbers / tables)
        out = new PrintWriter(outDir + "/data_xrefs.txt");
        di = p.getListing().getDefinedData(true);
        int dcount = 0;
        while (di.hasNext()) {
            Data d = di.next();
            if (d.hasStringValue()) continue;
            ReferenceIterator it = rm.getReferencesTo(d.getAddress());
            int rc = 0;
            StringBuilder rlist = new StringBuilder();
            while (it.hasNext()) {
                Reference r = it.next();
                rc++;
                if (rc <= 10) {
                    rlist.append(String.format(" 0x%08x", r.getFromAddress().getOffset()));
                }
            }
            if (rc > 0) {
                dcount++;
                String v = "";
                try { v = String.valueOf(d.getValue()); } catch (Exception e) { v = "?"; }
                if (v.length() > 60) v = v.substring(0, 60);
                String bname = "??";
                MemoryBlock blk = p.getMemory().getBlock(d.getAddress());
                if (blk != null) bname = blk.getName();
                out.printf("0x%08x %-10s %s |%s%n", d.getAddress().getOffset(), bname, v, rlist);
            }
        }
        out.close();
        println("Non-string data xrefs dumped: " + dcount);

        println("Dump complete in " + outDir);
    }
}
