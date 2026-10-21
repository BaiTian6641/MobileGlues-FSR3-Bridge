package com.googlebook.fsrbridge;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Bounded diagnostic writer, enabled by mgfsr3-trace.arm in the game
 * directory. Events accumulate in memory and are written once at a
 * bounded frame/event boundary; there is no per-draw file I/O.
 *
 * The class deliberately has no compile-time Minecraft types. Iris/Minecraft
 * lookups are reflective and any missing surface is recorded as unknown.
 */
public final class DiagnosticTrace {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FRAMES = 120;
    private static final int MAX_EVENTS = 3000;
    private static final int MAX_CHARS = 12 * 1024 * 1024;
    private static final int ARM_CHECK_FRAMES = 30;

    private static final int OP_ENABLE = 0;
    private static final int OP_START = 1;
    private static final int OP_SNAPSHOT = 2;
    private static final int OP_END = 3;
    private static final int OP_CONFIG = 4;
    private static final int OP_RESET = 5;

    private static final Path MARKER = Paths.get(System.getProperty("user.dir", "."), "mgfsr3-trace.arm");
    private static final Path OUTPUT = Paths.get(System.getProperty("user.dir", "."), "mgfsr3-trace.jsonl");

    private static volatile boolean enabled;
    private static boolean diagnosticWarning;
    private static boolean unavailableWarning;
    private static boolean finished;
    private static int frame;
    private static int armTicks;
    private static int eventCounter;
    private static int roleCounter;
    private static int eventCount;
    private static boolean overflow;
    private static String overflowReason;
    private static String configJson;
    private static String buildProperties;
    private static final java.util.LinkedHashSet<String> hooks = new java.util.LinkedHashSet<>();
    private static final StringBuilder events = new StringBuilder();
    private static String currentRole;
    private static int currentRoleId;
    private static boolean currentRoleCaptured;

    private DiagnosticTrace() {
    }

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(DiagnosticTrace::finish, "mgfsr3-trace-flush"));
        } catch (Exception | LinkageError failure) {
            diagnosticFailure(failure);
        }
    }

    private static synchronized void diagnosticFailure(Throwable failure) {
        if (!diagnosticWarning) {
            diagnosticWarning = true;
            System.err.println("[mgfsr3bridge] optional diagnostic unavailable: " + failure);
        }
    }

    public static boolean active() {
        return enabled && !finished;
    }

    public static synchronized void beginFrame(String phase) {
        if (!enabled) {
            if ((armTicks++ % ARM_CHECK_FRAMES) == 0) tryArm();
            if (!enabled) return;
        }
        if (frame >= MAX_FRAMES) {
            finish();
            return;
        }
        if (currentRole != null) {
            if (currentRoleCaptured) endRole("FRAME_END_" + currentRole, null);
            else endSilent();
        }
        frame++;
        if (frame == 1) {
            String refreshed = NativeBridge.trace(OP_CONFIG, 0, 0, 0);
            if (refreshed != null) configJson = refreshed;
        }
        currentRole = null;
        currentRoleCaptured = false;
        // Frame events are written through phase/hand/final hooks, not here.
    }

    public static synchronized void phase(String phase) {
        try {
            phaseBoundary(phase);
        } catch (Exception | LinkageError failure) {
            diagnosticFailure(failure);
        }
    }

    private static void phaseBoundary(String phase) {
        // beginFrame owns marker polling and the frame budget. A phase callback
        // outside a world frame must not arm a frame-zero capture.
        if (!active() || frame == 0) return;
        hooks.add("Pipeline.setPhase");
        if (("PHASE_" + phase).equals(currentRole)) return;
        // Hand, shadow, composite and final hooks own their role across the
        // phase calls that occur inside those passes. Do not truncate them.
        if (currentRole != null && (currentRole.startsWith("HAND_") ||
                currentRole.startsWith("SHADOW") || currentRole.startsWith("COMPOSITE") ||
                currentRole.startsWith("FINAL_"))) return;
        if (currentRole != null) {
            if (currentRoleCaptured) {
                endRole(currentRole + "_END", null);
            } else {
                endSilent();
            }
        }
        if ("NONE".equals(phase)) return;
        if ("HAND_SOLID".equals(phase) || "HAND_TRANSLUCENT".equals(phase)) {
            // HandRenderer owns the role; setPhase precedes it and has no draw.
            return;
        }
        if (shouldCapturePhase(phase)) {
            startRole("PHASE_" + phase, true);
        }
    }

    private static boolean shouldCapturePhase(String phase) {
        if (phase.startsWith("TERRAIN_")) return true;
        if (frame <= 2) return true;
        if ((frame % 30) == 0) return true;
        return phase.startsWith("SKY") || phase.startsWith("STARS") || "CUSTOM_SKY".equals(phase);
    }

    public static synchronized void startRole(String role, boolean captured) {
        if (!active()) return;
        if (currentRole != null) {
            if (currentRoleCaptured) endRole(currentRole + "_REPLACED", null);
            else endSilent();
        }
        currentRole = role;
        currentRoleId = ++roleCounter;
        currentRoleCaptured = captured;
        NativeBridge.trace(OP_START, currentRoleId, frame, eventCounter + 1);
    }

    public static synchronized void snapshot(String name, String extraJson) {
        if (!active()) return;
        if (currentRole == null) {
            appendEvent(name, null, extraJson, NativeBridge.trace(OP_SNAPSHOT, 0, frame, eventCounter + 1));
            return;
        }
        appendEvent(name, currentRole, extraJson, NativeBridge.trace(OP_SNAPSHOT, currentRoleId, frame, eventCounter + 1));
    }

    public static synchronized void endRole(String name, String extraJson) {
        if (!active()) return;
        String role = currentRole;
        int id = currentRoleId;
        currentRole = null;
        currentRoleCaptured = false;
        String json = NativeBridge.trace(OP_END, id, frame, eventCounter + 1);
        appendEvent(name, role, extraJson, json);
    }

    private static void endSilent() {
        if (currentRole == null) return;
        NativeBridge.trace(OP_END, currentRoleId, frame, eventCounter + 1);
        currentRole = null;
        currentRoleCaptured = false;
    }

    public static synchronized void plain(String name, String extraJson) {
        if (!active()) return;
        appendEvent(name, currentRole, extraJson, NativeBridge.trace(OP_SNAPSHOT, 0, frame, eventCounter + 1));
    }


    /** Report Java/JNI outcome only; this event performs no additional GL query. */
    public static synchronized void submitResult(boolean attempted, Integer result,
                                                  MainSubmissionTarget target, String stage,
                                                  Throwable failure, boolean depthNegotiated) {
        if (!active()) return;
        try {
            StringBuilder extra = new StringBuilder(256);
            extra.append('{');
            boolean first = rawField(extra, true, "attempted", String.valueOf(attempted));
            first = integerField(extra, first, "native_rc", result);
            first = field(extra, first, "stage", stage);
            first = field(extra, first, "exception_class", failure == null ? null : failure.getClass().getName());
            boolean verified = target != null && target.colorTexture() > 0;
            first = rawField(extra, first, "main_target_verified", String.valueOf(verified));
            first = integerField(extra, first, "main_color_texture", verified ? target.colorTexture() : null);
            first = integerField(extra, first, "main_width", verified ? target.width() : null);
            first = integerField(extra, first, "main_height", verified ? target.height() : null);
            first = rawField(extra, first, "sampled_depth_reversed", String.valueOf(AdapterProfile.depthConvention() == 1));
            rawField(extra, first, "depth_convention_negotiated", String.valueOf(depthNegotiated));
            extra.append('}');
            appendEvent("SUBMIT_RESULT", null, extra.toString(), null);
        } catch (Exception | LinkageError diagnosticError) {
            diagnosticFailure(diagnosticError);
        }
    }

    public static synchronized void hook(String name) {
        hooks.add(name);
    }

    public static synchronized void hudEntry() {
        plain("HUD_ENTRY", null);
        if (frame >= MAX_FRAMES) finish();
    }

    /** Allocation/resize metadata; reflection only, never a GL query. */
    public static synchronized void irisTargets(Object renderTargets, String event) {
        if (!active()) return;
        plain(event, reflectTargets(renderTargets));
    }

    /** Pre-hand/pre-translucent copy metadata. */
    public static synchronized void copyEntry(String role, Object renderTargets) {
        if (!active()) return;
        String extra = copyMetadata(renderTargets, role.contains("HAND"));
        startRole(role, true);
        snapshot(role + "_ENTRY", extra);
    }

    private static String copyMetadata(Object renderTargets, boolean hand) {
        Object dirtyValue = reflect(renderTargets, hand ? "handDepthDirty" : "translucentDepthDirty");
        Object strategy = reflect(renderTargets, "copyStrategy");
        Object width = reflect(renderTargets, "cachedWidth");
        Object height = reflect(renderTargets, "cachedHeight");
        Boolean dirty = dirtyValue == null ? null : Boolean.valueOf(Boolean.TRUE.equals(dirtyValue));
        StringBuilder extra = new StringBuilder(256);
        extra.append('{');
        boolean first = true;
        first = rawField(extra, first, "dirty", dirty == null ? null : String.valueOf(dirty));
        first = field(extra, first, "dirty_path", dirty == null ? null
                : (dirty ? "copyTexImage2D" : "depth_copy_strategy"));
        first = field(extra, first, "strategy", strategy == null ? null : strategy.getClass().getName());
        first = integerField(extra, first, "width", width);
        first = integerField(extra, first, "height", height);
        first = callTextureMethod(extra, first, renderTargets, "getDepthTexture", "source_depth_texture");
        first = reflectTextureId(extra, first, renderTargets, "noHand", "noHand_texture");
        first = reflectFboId(extra, first, renderTargets, "noHandDestFb", "noHand_dest_fbo");
        first = reflectTextureId(extra, first, renderTargets, "noTranslucents", "noTranslucents_texture");
        first = reflectFboId(extra, first, renderTargets, "noTranslucentsDestFb", "noTranslucents_dest_fbo");
        extra.append('}');
        return extra.toString();
    }

    public static synchronized void copyExit(String role) {
        if (!active()) return;
        endRole(role + "_EXIT", null);
    }

    private static void appendEvent(String name, String role, String extraJson, String glJson) {
        if (finished) return;
        if (eventCount >= MAX_EVENTS || events.length() >= MAX_CHARS) {
            overflow = true;
            overflowReason = eventCount >= MAX_EVENTS ? "event_limit" : "byte_limit";
            finish();
            return;
        }
        StringBuilder line = new StringBuilder(256);
        line.append('{');
        line.append("\"schema_version\":").append(SCHEMA_VERSION);
        line.append(",\"kind\":\"event\"");
        line.append(",\"event\":");
        quote(line, name);
        line.append(",\"frame\":").append(frame);
        line.append(",\"event_id\":").append(++eventCounter);
        line.append(",\"role\":");
        if (role == null) line.append("null");
        else quote(line, role);
        line.append(",\"t_ns\":").append(System.nanoTime());
        if (extraJson != null) {
            line.append(",\"iris\":").append(extraJson);
        }
        line.append(",\"gl\":");
        line.append(glJson == null ? "null" : glJson);
        line.append('}').append('\n');
        if (events.length() + line.length() > MAX_CHARS) {
            overflow = true;
            overflowReason = "byte_limit";
            finish();
            return;
        }
        events.append(line);
        eventCount++;
    }

    private static void tryArm() {
        if (enabled || finished) return;
        try {
            if (!Files.isRegularFile(MARKER)) return;
            String config = NativeBridge.trace(OP_CONFIG, 0, 0, 0);
            if (config == null) {
                if (!unavailableWarning) {
                    unavailableWarning = true;
                    Fsr3Bridge.mark("trace marker present but diagnostic core API is unavailable; "
                            + "build the renderer with MG_FSR3_DIAGNOSTICS=ON");
                }
                return;
            }
            configJson = config;
            buildProperties = readBuildProperties();
            NativeBridge.trace(OP_ENABLE, 1, 0, 0);
            enabled = true;
            finished = false;
            frame = 0;
            armTicks = 0;
            eventCounter = 0;
            roleCounter = 0;
            eventCount = 0;
            overflow = false;
            overflowReason = null;
            events.setLength(0);
            currentRole = null;
            currentRoleCaptured = false;
            Fsr3Bridge.mark("trace armed: " + OUTPUT);
        } catch (Exception | LinkageError t) {
            Fsr3Bridge.mark("trace arm failed: " + t);
        }
    }

    public static synchronized void finish() {
        if (!enabled || finished) return;
        finished = true;
        enabled = false;
        try {
            String manifest = manifestJson();
            String body = manifest + "\n" + events;
            Files.writeString(OUTPUT, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(MARKER);
            Fsr3Bridge.mark("trace written: " + OUTPUT + " events=" + eventCount
                    + " frames=" + frame + (overflow ? " overflow=" + overflowReason : ""));
        } catch (Exception | LinkageError t) {
            Fsr3Bridge.mark("trace write failed: " + t);
        } finally {
            NativeBridge.trace(OP_RESET, 0, 0, 0);
            currentRole = null;
        }
    }

    private static String manifestJson() {
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        out.append("\"schema_version\":").append(SCHEMA_VERSION);
        out.append(",\"kind\":\"manifest\"");
        out.append(",\"config\":").append(configJson == null ? "null" : configJson);
        out.append(",\"build\":");
        if (buildProperties == null) out.append("null");
        else quote(out, buildProperties);
        out.append(",\"pack\":");
        appendPack(out);
        out.append(",\"hooks\":");
        out.append('[');
        boolean hf = true;
        for (String hook : hooks) {
            if (!hf) out.append(',');
            hf = false;
            quote(out, hook);
        }
        out.append(']');
        out.append(",\"runtime\":");
        appendRuntime(out);
        out.append(",\"bounds\":{\"max_frames\":").append(MAX_FRAMES)
                .append(",\"max_events\":").append(MAX_EVENTS)
                .append(",\"max_chars\":").append(MAX_CHARS)
                .append(",\"frames\":").append(frame)
                .append(",\"events\":").append(eventCount)
                .append(",\"overflow\":").append(overflow)
                .append(",\"overflow_reason\":");
        if (overflowReason == null) out.append("null");
        else quote(out, overflowReason);
        out.append('}');
        out.append(",\"missing\":[\"pack_archive_sha256\",\"pack_option_map\",\"helper_hash_at_runtime\",\"apk_hash_at_runtime\"]");
        out.append('}');
        return out.toString();
    }

    private static void appendPack(StringBuilder out) {
        out.append('{');
        boolean first = true;
        first = field(out, first, "name", callStatic(AdapterProfile.irisClass("Iris"), "getCurrentPackName"));
        first = rawField(out, first, "in_use", callStaticBool(AdapterProfile.irisClass("Iris"), "isPackInUseQuick"));
        first = field(out, first, "profile_info", callPackProfile());
        first = field(out, first, "iris_version", callStatic(AdapterProfile.irisClass("Iris"), "getVersionSimple"));
        first = field(out, first, "archive_sha256", null);
        out.append('}');
    }

    private static void appendRuntime(StringBuilder out) {
        out.append('{');
        boolean first = true;
        first = field(out, first, "manufacturer", androidValue("MANUFACTURER"));
        first = field(out, first, "model", androidValue("MODEL"));
        first = field(out, first, "android_release", androidValue("RELEASE"));
        first = field(out, first, "sdk", androidValue("SDK_INT"));
        first = field(out, first, "user_dir", System.getProperty("user.dir"));
        first = field(out, first, "java_version", System.getProperty("java.version"));
        out.append('}');
    }

    private static boolean field(StringBuilder out, boolean first, String name, String value) {
        if (!first) out.append(',');
        quote(out, name);
        out.append(':');
        if (value == null) out.append("null");
        else quote(out, value);
        return false;
    }

    private static boolean rawField(StringBuilder out, boolean first, String name, String raw) {
        if (!first) out.append(',');
        quote(out, name);
        out.append(':');
        out.append(raw == null ? "null" : raw);
        return false;
    }

    private static String callStaticBool(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            Object value = type.getMethod(methodName).invoke(null);
            if (value instanceof Boolean) return String.valueOf(value);
            return null;
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static String callStatic(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            Object value = type.getMethod(methodName).invoke(null);
            return value == null ? null : String.valueOf(value);
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static String callPackProfile() {
        try {
            Class<?> iris = Class.forName(AdapterProfile.irisClass("Iris"));
            Object optional = iris.getMethod("getCurrentPack").invoke(null);
            if (!(optional instanceof java.util.Optional)) return null;
            Object pack = ((java.util.Optional<?>) optional).orElse(null);
            if (pack == null) return null;
            return String.valueOf(pack.getClass().getMethod("getProfileInfo").invoke(pack));
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static String androidValue(String fieldName) {
        try {
            boolean versionField = "SDK_INT".equals(fieldName) || "RELEASE".equals(fieldName);
            Class<?> build = Class.forName(versionField ? "android.os.Build$VERSION" : "android.os.Build");
            Object value = build.getField(fieldName).get(null);
            return String.valueOf(value);
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static String readBuildProperties() {
        try (java.io.InputStream in = DiagnosticTrace.class.getResourceAsStream("/trace-build.properties")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static String reflectTargets(Object renderTargets) {
        if (renderTargets == null) return null;
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        boolean first = true;
        first = field(out, first, "class", renderTargets.getClass().getName());
        first = reflectInt(out, first, renderTargets, "cachedWidth");
        first = reflectInt(out, first, renderTargets, "cachedHeight");
        Object targets = reflect(renderTargets, "targets");
        if (targets instanceof Object[]) {
            Object[] array = (Object[]) targets;
            out.append(first ? "" : ",").append("\"render_targets\":[");
            first = false;
            for (int i = 0; i < array.length; ++i) {
                if (i > 0) out.append(',');
                out.append('{');
                boolean itemFirst = true;
                itemFirst = rawField(out, itemFirst, "index", String.valueOf(i));
                Object target = array[i];
                itemFirst = reflectInt(out, itemFirst, target, "width");
                itemFirst = reflectInt(out, itemFirst, target, "height");
                itemFirst = reflectInt(out, itemFirst, target, "mainTexture");
                itemFirst = reflectInt(out, itemFirst, target, "altTexture");
                itemFirst = reflectObject(out, itemFirst, target, "internalFormat");
                out.append('}');
            }
            out.append(']');
        } else {
            out.append(first ? "" : ",").append("\"render_targets\":null");
            first = false;
        }
        first = reflectTexture(out, first, renderTargets, "noHand", "noHand");
        first = reflectTexture(out, first, renderTargets, "noTranslucents", "noTranslucents");
        first = reflectFbo(out, first, renderTargets, "depthSourceFb", "depthSourceFb");
        first = reflectFbo(out, first, renderTargets, "noHandDestFb", "noHandDestFb");
        first = reflectFbo(out, first, renderTargets, "noTranslucentsDestFb", "noTranslucentsDestFb");
        out.append('}');
        return out.toString();
    }


    private static boolean callTextureMethod(StringBuilder out, boolean first, Object instance, String method,
                                         String label) {
        try {
            Object value = instance.getClass().getMethod(method).invoke(instance);
            return integerField(out, first, label, textureId(value));
        } catch (Exception | LinkageError t) {
            return rawField(out, first, label, null);
        }
    }

    private static boolean reflectTextureId(StringBuilder out, boolean first, Object instance, String field,
                                            String label) {
        return integerField(out, first, label, textureId(reflect(instance, field)));
    }

    private static Integer textureId(Object texture) {
        if (texture == null) return null;
        if (texture instanceof Integer id) return id;
        try {
            return AdapterProfile.textureId(texture);
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static boolean reflectFboId(StringBuilder out, boolean first, Object instance, String field,
                                        String label) {
        Object fbo = reflect(instance, field);
        if (fbo == null) return rawField(out, first, label, null);
        try {
            Object value = fbo.getClass().getMethod("getId").invoke(fbo);
            return integerField(out, first, label, value);
        } catch (Exception | LinkageError t) {
            return rawField(out, first, label, null);
        }
    }

    private static Object reflect(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private static boolean reflectInt(StringBuilder out, boolean first, Object instance, String name) {
        return integerField(out, first, name, reflect(instance, name));
    }

    private static boolean integerField(StringBuilder out, boolean first, String name, Object value) {
        String number = value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte ? value.toString() : null;
        return rawField(out, first, name, number);
    }

    private static boolean reflectObject(StringBuilder out, boolean first, Object instance, String name) {
        Object value = reflect(instance, name);
        return field(out, first, name, value == null ? null : String.valueOf(value));
    }

    private static boolean reflectTexture(StringBuilder out, boolean first, Object instance, String field,
                                          String label) {
        return integerField(out, first, label + "_texture", textureId(reflect(instance, field)));
    }

    private static boolean reflectFbo(StringBuilder out, boolean first, Object instance, String field, String label) {
        Object fbo = reflect(instance, field);
        if (fbo == null) return field(out, first, label + "_fbo", null);
        String id = null;
        try {
            id = String.valueOf(fbo.getClass().getMethod("getId").invoke(fbo));
        } catch (Exception | LinkageError failure) {
            diagnosticFailure(failure);
        }
        return field(out, first, label + "_fbo", id);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '"': out.append("\\\""); break;
            case '\\': out.append("\\\\"); break;
            case '\b': out.append("\\b"); break;
            case '\f': out.append("\\f"); break;
            case '\n': out.append("\\n"); break;
            case '\r': out.append("\\r"); break;
            case '\t': out.append("\\t"); break;
            default:
                if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
        }
        out.append('"');
    }
}
