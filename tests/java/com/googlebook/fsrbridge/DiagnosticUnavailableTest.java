package com.googlebook.fsrbridge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Exercise the actual optional JNI symbol and repeated marker polling. */
public final class DiagnosticUnavailableTest {
    private DiagnosticUnavailableTest() {}

    static void verify() throws Exception {
        var folder = Files.createTempDirectory("fsr-trace-unavailable-");
        String originalDirectory = System.getProperty("user.dir");
        PrintStream originalError = System.err;
        var output = new ByteArrayOutputStream();
        var marker = folder.resolve("mgfsr3-trace.arm");
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            Files.createFile(marker);
            System.setProperty("user.dir", folder.toString());
            System.setErr(capture);
            for (int frame = 0; frame < 300; frame++) DiagnosticTrace.beginFrame("TEST");
            DiagnosticTrace.finish();
            String warning = "trace marker present but diagnostic core API is unavailable";
            String text = output.toString(StandardCharsets.UTF_8);
            if (text.indexOf(warning) < 0 || text.indexOf(warning) != text.lastIndexOf(warning)) {
                throw new AssertionError("unavailable diagnostics must produce exactly one warning");
            }
            if (DiagnosticTrace.active() || !Files.exists(marker)
                    || Files.exists(folder.resolve("mgfsr3-trace.jsonl"))) {
                throw new AssertionError("unavailable diagnostics must preserve the marker without producing a trace");
            }
        } finally {
            System.setErr(originalError);
            System.setProperty("user.dir", originalDirectory);
            Files.deleteIfExists(marker);
            Files.delete(folder);
        }
        System.out.println("PASS optional native diagnostic absence produces one warning across 300 frames");
    }
}
