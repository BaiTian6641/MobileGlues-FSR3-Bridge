package com.googlebook.fsrbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Executes real Java/JNI control flow; the native fixture supplies no renderer. */
public final class DiagnosticFlowTest {
    private DiagnosticFlowTest() { }
    private static native void control(int result, boolean submitThrows, boolean traceThrows);
    private static native int calls(boolean trace);

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object state(String name) throws ReflectiveOperationException {
        var field = DiagnosticTrace.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void phase(String name) throws ReflectiveOperationException {
        Class.forName("com.googlebook.fsrbridge.DiagnosticPipelineHook")
                .getMethod("call", String.class).invoke(null, name);
    }

    private static void frame() throws ReflectiveOperationException {
        DiagnosticTrace.beginFrame("TEST");
        for (String phase : new String[]{"TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
                                         "TERRAIN_CUTOUT", "TERRAIN_TRANSLUCENT", "NONE"}) {
            phase(phase);
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        System.setProperty("user.dir", output.toString());
        Files.createFile(output.resolve("mgfsr3-trace.arm"));
        System.load(args[0]);
        System.load(args[1]);
        var helperLoaded = NativeBridge.class.getDeclaredField("helperLoaded");
        helperLoaded.setAccessible(true);
        helperLoaded.setBoolean(null, true);
        System.setProperty("mg.fsr3.library", args[1]);
        Class.forName(Fsr3Bridge.class.getName());
        var loaded = Fsr3Bridge.class.getDeclaredField("LOADED");
        loaded.setAccessible(true);
        check(loaded.getBoolean(null), "host core initialized and negotiated depth; no GL context claimed");
        phase("TERRAIN_SOLID");
        check(!DiagnosticTrace.active() && calls(true) == 0, "phase cannot arm outside beginFrame");
        DiagnosticTrace.beginFrame("TEST");
        phase("TERRAIN_SOLID");
        int before = calls(true);
        phase("TERRAIN_SOLID");
        check(calls(true) == before, "duplicate phase retains accumulated draw role");
        check("PHASE_TERRAIN_SOLID".equals(state("currentRole")), "opaque terrain role entered");
        DiagnosticTrace.startRole("SHADOW", true);
        phase("TERRAIN_CUTOUT");
        check("SHADOW".equals(state("currentRole")), "phase cannot truncate shadow role");
        DiagnosticTrace.endRole("SHADOW_RETURN", null);
        control(-23, false, true);
        phase("TERRAIN_TRANSLUCENT");
        control(-23, false, false);
        phase("NONE"); // Optional failure did not escape or prevent next boundary.

        boolean modern = AdapterProfile.value("minecraft").equals("26.2");
        Fsr3Bridge.submit(null);
        check(calls(false) == (modern ? 0 : 1), "target rejection is not a native attempt");
        if (!modern) {
            control(0, false, false);
            Fsr3Bridge.submit(null);
            control(17, true, false);
            Fsr3Bridge.submit(null);
            check(calls(false) == 3, "one native call per submission including thrown attempt");
        }
        control(0, false, false);
        GL30.status = 0x8CD5;
        GL30.attachmentTexture = 99;
        GL30.attachmentType = 0x1702;
        GL30.attachmentLevel = 0;
        GL11.textureWidth = 2880;
        GL11.textureHeight = 1585;
        try (MainSubmissionTarget target = MainSubmissionTarget.bind(50, 99, 2880, 1585)) {
            before = calls(true);
            DiagnosticTrace.submitResult(true, 0, target, "complete", null, true);
            check(calls(true) == before, "submission outcome performs no extra native/GL query");
        }
        DiagnosticTrace.submitResult(true, 0, null, "target_restore",
                new IllegalStateException("private/path"), true);
        DiagnosticTrace.submitResult(false, null, null, "unavailable", null, false);
        if (args[3].equals("events")) {
            for (int i = 0; i < 4000; i++) DiagnosticTrace.submitResult(false, null, null,
                    "unavailable", null, false);
            check(!DiagnosticTrace.active(), "event budget stops capture");
        } else {
            while ((Integer) state("frame") < 120) frame();
            DiagnosticTrace.beginFrame("TEST");
            check(!DiagnosticTrace.active(), "frame budget stops capture");
        }
        DiagnosticTrace.finish();
        check(!Files.exists(output.resolve("mgfsr3-trace.arm")), "successful capture consumes marker");
        System.out.println("TRACE_OUTPUT " + output.resolve("mgfsr3-trace.jsonl"));
        System.out.println("PASS real diagnostic phase/submission control flow and capture bounds (host transport only)");
    }
}
