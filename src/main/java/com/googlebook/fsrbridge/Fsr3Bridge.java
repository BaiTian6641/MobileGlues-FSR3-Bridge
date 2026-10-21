package com.googlebook.fsrbridge;


/**
 * Optional Minecraft/Iris connector. It owns no renderer logic: after Iris has
 * finished its final pass, the native MobileGlues library resolves the current
 * main framebuffer's color/depth attachments and runs the in-tree FSR3
 * backend. The bridge is only a semantic boundary, not a second upscaler.
 *
 * The class deliberately exposes no game types. Each artifact supplies an exact
 * Iris/Oculus adapter through its generated mixins and profile resource.
 */
public final class Fsr3Bridge {
    private static final boolean LOADED;
    private static volatile boolean warned;

    static {
        LOADED = loadNative();
        if (LOADED && AdapterProfile.hasOpenGlContext()) {
            try {
                NativeBridge.bridgeMode();
            } catch (Exception | LinkageError t) {
                mark("bridge mode negotiation unavailable: " + t);
            }
        }
        mark("Fsr3Bridge loaded=" + LOADED);
    }

    private Fsr3Bridge() {
    }

    private static String loadedCorePath() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/maps"))) {
                int slash = line.indexOf('/');
                if (slash < 0) continue;
                String path = line.substring(slash).trim();
                if (path.endsWith("/libmobileglues.so")) {
                    int space = path.indexOf(' ');
                    if (space > 0) path = path.substring(0, space);
                    return path;
                }
            }
        } catch (Exception | LinkageError failure) {
            warn("optional bridge cleanup/resolution failed: " + failure);
        }
        return null;
    }

    private static boolean loadNative() {
        String path = System.getProperty("mg.fsr3.library");
        if (path == null || path.isEmpty()) {
            path = loadedCorePath();
        }
        if (path == null) {
            warn("no mapped MobileGlues library path");
            return false;
        }
        mark("helper init " + path);
        if (!NativeBridge.load(path)) return false;
        if (!NativeBridge.depthConvention(AdapterProfile.depthConvention())) {
            mark("renderer does not support this adapter's depth convention; bridge disabled");
            return false;
        }
        return true;
    }

    private static boolean cameraWarning;

    /**
     * Capture Iris' current camera matrices for camera-only reprojection.
     * Reflection keeps this adapter independent of Minecraft/JOML at build time;
     * if the class or matrix accessor is absent, the native side keeps the
     * synthetic zero-motion path.
     */
    public static void captureCamera() {
        if (!LOADED) return;
        try {
            Class<?> stateClass = Class.forName(AdapterProfile.irisClass("uniforms.CapturedRenderingState"));
            Object state = stateClass.getField("INSTANCE").get(null);
            Object modelView = stateClass.getMethod("getGbufferModelView").invoke(state);
            Object projection = stateClass.getMethod("getGbufferProjection").invoke(state);
            Class<?> cameraClass = Class.forName(AdapterProfile.irisClass("uniforms.CameraUniforms"));
            Object position = cameraClass.getMethod("getUnshiftedCameraPosition").invoke(null);
            float[] mv = new float[16];
            float[] pr = new float[16];
            double[] pos = new double[3];
            AdapterProfile.copyMatrix(modelView, mv);
            AdapterProfile.copyMatrix(projection, pr);
            pos[0] = AdapterProfile.vectorComponent(position, 0);
            pos[1] = AdapterProfile.vectorComponent(position, 1);
            pos[2] = AdapterProfile.vectorComponent(position, 2);
            NativeBridge.camera(mv, pr, pos);
        } catch (Exception | LinkageError t) {
            if (!cameraWarning) {
                cameraWarning = true;
                warn("camera matrices unavailable; using zero motion: " + t);
            }
            try {
                NativeBridge.invalidateCamera();
            } catch (Exception | LinkageError failure) {
                warn("camera invalidation failed: " + failure);
            }
        }
    }

    public static void mark(String message) {
        // Launcher logs already capture stderr. Avoid an unbounded second file in the game directory.
        System.err.println("[mgfsr3bridge] " + message);
    }

    private static void warn(String message) {
        if (warned) return;
        warned = true;
        mark("WARN " + message);
    }

    public static boolean available() {
        if (!LOADED || !packHasFinalPass || !AdapterProfile.hasOpenGlContext()) return false;
        if (!AdapterProfile.supportsSceneScaling() && NativeBridge.scale() > 1.001f) return false;
        try {
            boolean available = NativeBridge.available();
            if (available && !warned) {
                warned = true;
                mark("nativeAvailable=true");
            }
            return available;
        } catch (Exception | LinkageError t) {
            warn("nativeAvailable failed: " + t);
            return false;
        }
    }

    /**
     * Scale an Iris scene target dimension. Returns the input unchanged unless
     * FSR3 is enabled and a non-native quality preset is selected. Minecraft
     * only: this is the optional adapter boundary, not a generic renderer hook.
     */
    private static volatile boolean inFinalPass;
    private static volatile boolean packHasFinalPass = true;
    private static FinalViewportUniforms finalViewportUniforms;

    /**
     * Iris' no-final path copies colortex0 into the native main target with
     * glCopyTexSubImage2D. It cannot consume a bridge-scaled source. Disable
     * the bridge for such packs rather than producing a lower-left-only frame.
     */
    public static void setPackHasFinalPass(boolean hasFinalPass) {
        packHasFinalPass = hasFinalPass;
        mark("pack has final pass=" + hasFinalPass);
    }

    public static void beginFinalPass(FinalViewportUniforms uniforms) {
        inFinalPass = true;
        finalViewportUniforms = uniforms;
        uniforms.mgfsr3$refreshViewportUniforms();
    }

    public static void endFinalPass() {
        inFinalPass = false;
        FinalViewportUniforms uniforms = finalViewportUniforms;
        finalViewportUniforms = null;
        if (uniforms != null) uniforms.mgfsr3$refreshViewportUniforms();
    }

    /** Recover a final-pass scope if Iris aborted a previous frame before its return injection. */
    public static void resetFinalPassScope() {
        try {
            if (LOADED && inFinalPass) {
                NativeBridge.finalScope(false);
                NativeBridge.setSceneDepth(0);
            }
        } finally {
            endFinalPass();
        }
    }

    /** World/composite programs get the scene extent; the final pass gets native. */
    public static int uniformWidth(int width) {
        return inFinalPass ? width : sceneWidth(width);
    }

    public static int uniformHeight(int height) {
        return inFinalPass ? height : sceneHeight(height);
    }

    private static Object managedDepth;
    private static int managedWidth;
    private static int managedHeight;
    private static int managedFormat;
    private static int managedUsage;
    private static int managedDepthFbo;
    private record RetiredDepth(Object texture, int framebuffer) {}
    private static final java.util.List<RetiredDepth> retiredDepths = new java.util.ArrayList<>();

    /**
     * Give the scaled Iris color targets a matching scene depth.
     * Minecraft's main depth remains native for the final/HUD surface; this
     * texture is owned by the bridge and is not the noHand snapshot.
     */
    public static synchronized int sceneDepthTexture(int original, int width, int height) {
        return allocateSceneDepth(original, width, height, null);
    }

    /** 26.2 owns a GpuTexture object; retain that ownership contract while replacing its extent. */
    public static synchronized Object sceneDepthObject(Object original, int width, int height) {
        if (!LOADED || !packHasFinalPass || !AdapterProfile.hasOpenGlContext() || NativeBridge.scale() <= 1.001f) {
            retireManagedDepth();
            return original;
        }
        int originalId = AdapterProfile.textureId(original);
        int selected = allocateSceneDepth(originalId, width, height, original);
        return selected == originalId ? original : managedDepth;
    }

    private static int allocateSceneDepth(int original, int width, int height, Object sourceGpu) {
        if (!LOADED || !packHasFinalPass || !AdapterProfile.supportsSceneScaling() || width <= 0 || height <= 0) return original;
        try {
            if (NativeBridge.scale() <= 1.001f) return original;
            int format = queryTextureFormat(original);
            if (format == 0) throw new IllegalStateException("scene depth format unavailable");
            int usage = sourceGpu == null ? 0 : AdapterProfile.gpuUsage(sourceGpu);
            if (managedDepth != null && managedWidth == width && managedHeight == height
                    && managedFormat == format && managedUsage == usage) {
                return AdapterProfile.textureId(managedDepth);
            }
            Object next = AdapterProfile.createDepth(width, height, format, sourceGpu);
            int nextId;
            int nextFbo = 0;
            boolean ready = false;
            try {
                nextId = AdapterProfile.textureId(next);
                nextFbo = createDepthFbo(nextId);
                if (nextFbo == 0) throw new IllegalStateException("scene depth framebuffer incomplete");
                ready = true;
            } finally {
                if (!ready) destroyDepthResource(new RetiredDepth(next, nextFbo));
            }
            Object previous = managedDepth;
            int previousFbo = managedDepthFbo;
            managedDepth = next;
            managedDepthFbo = nextFbo;
            managedWidth = width;
            managedHeight = height;
            managedFormat = format;
            managedUsage = usage;
            if (previous != null) {
                RetiredDepth retired = new RetiredDepth(previous, previousFbo);
                if (sourceGpu != null) retiredDepths.add(retired);
                else destroyDepthResource(retired);
            }
            mark("scene depth " + width + "x" + height + " id=" + nextId + " format=" + format);
            return nextId;
        } catch (Exception | LinkageError t) {
            throw new IllegalStateException("cannot allocate coherent scene color/depth", t);
        }
    }

    public static synchronized int sceneDepthTextureId() {
        return managedDepth == null ? 0 : AdapterProfile.textureId(managedDepth);
    }

    public static synchronized void destroySceneDepth() {
        retireManagedDepth();
        completeSceneDepthUpdate();
    }

    /** Defer deletion until Iris has replaced every cached depth reference and attachment. */
    private static void retireManagedDepth() {
        if (managedDepth != null) retiredDepths.add(new RetiredDepth(managedDepth, managedDepthFbo));
        managedDepth = null;
        managedDepthFbo = 0;
        managedWidth = managedHeight = managedFormat = managedUsage = 0;
    }

    /** A throwing Iris resize deliberately leaves retired resources alive until retry or teardown. */
    public static synchronized void completeSceneDepthUpdate() {
        for (RetiredDepth resource : retiredDepths) destroyDepthResource(resource);
        retiredDepths.clear();
    }

    private static void destroyDepthResource(RetiredDepth resource) {
        try {
            AdapterProfile.destroyDepth(resource.texture());
        } catch (Exception | LinkageError failure) {
            warn("scene depth cleanup failed: " + failure);
        } finally {
            if (resource.framebuffer() != 0) deleteFbo(resource.framebuffer());
        }
    }

    private static int createDepthFbo(int texture) {
        int fbo = 0;
        boolean complete = false;
        try {
            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            int[] previousDraw = new int[1], previousRead = new int[1];
            gl11.getMethod("glGetIntegerv", int.class, int[].class).invoke(null, 0x8CA6, previousDraw);
            gl11.getMethod("glGetIntegerv", int.class, int[].class).invoke(null, 0x8CAA, previousRead);
            fbo = (Integer) gl30.getMethod("glGenFramebuffers").invoke(null);
            try {
                gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CA9, fbo);
                gl30.getMethod("glFramebufferTexture2D", int.class, int.class, int.class, int.class, int.class)
                        .invoke(null, 0x8CA9, 0x8D00, 0x0DE1, texture, 0);
                int status = (Integer) gl30.getMethod("glCheckFramebufferStatus", int.class).invoke(null, 0x8CA9);
                complete = status == 0x8CD5;
                if (!complete) warn("scene depth FBO incomplete: " + Integer.toHexString(status));
            } finally {
                try {
                    gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CA9, previousDraw[0]);
                } finally {
                    gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CA8, previousRead[0]);
                }
            }
            return complete ? fbo : 0;
        } catch (ReflectiveOperationException | LinkageError failure) {
            complete = false;
            warn("scene depth FBO creation failed: " + failure);
            return 0;
        } finally {
            if (!complete && fbo != 0) deleteFbo(fbo);
        }
    }

    private static void deleteFbo(int fbo) {
        try {
            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
            gl30.getMethod("glDeleteFramebuffers", int.class).invoke(null, fbo);
        } catch (Exception | LinkageError failure) {
            warn("optional bridge cleanup/resolution failed: " + failure);
        }
    }

    public static void clearSceneDepth() {
        if (managedDepthFbo == 0) return;
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
            int[] previousDraw = new int[1], previousRead = new int[1];
            gl11.getMethod("glGetIntegerv", int.class, int[].class)
                    .invoke(null, 0x8CA6, previousDraw);
            gl11.getMethod("glGetIntegerv", int.class, int[].class)
                    .invoke(null, 0x8CAA, previousRead);
            int[] scissor = new int[1];
            gl11.getMethod("glGetIntegerv", int.class, int[].class)
                    .invoke(null, 0x0C11, scissor);
            int[] depthMask = new int[1];
            gl11.getMethod("glGetIntegerv", int.class, int[].class)
                    .invoke(null, 0x0B72, depthMask);
            float[] clearDepth = new float[1];
            gl11.getMethod("glGetFloatv", int.class, float[].class)
                    .invoke(null, 0x0B73, clearDepth);
            try {
                gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CA9, managedDepthFbo);
                if (scissor[0] != 0) gl11.getMethod("glDisable", int.class).invoke(null, 0x0C11);
                gl11.getMethod("glDepthMask", boolean.class).invoke(null, true);
                gl11.getMethod("glClearDepth", double.class).invoke(null, AdapterProfile.sceneDepthClearValue());
                gl11.getMethod("glClear", int.class).invoke(null, 0x0100);
            } finally {
                try {
                    gl11.getMethod("glClearDepth", double.class).invoke(null, (double) clearDepth[0]);
                } finally {
                    try {
                        gl11.getMethod("glDepthMask", boolean.class).invoke(null, depthMask[0] != 0);
                    } finally {
                        try {
                            gl11.getMethod(scissor[0] != 0 ? "glEnable" : "glDisable", int.class).invoke(null, 0x0C11);
                        } finally {
                            try {
                                gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CA9, previousDraw[0]);
                            } finally {
                                gl30.getMethod("glBindFramebuffer", int.class, int.class).invoke(null, 0x8CAA, previousRead[0]);
                            }
                        }
                    }
                }
            }
        } catch (Exception | LinkageError t) {
            throw new IllegalStateException("scene depth clear failed", t);
        }
    }

    private static int queryTextureFormat(int texture) {
        if (texture == 0) return 0;
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            int[] binding = new int[1];
            gl11.getMethod("glGetIntegerv", int.class, int[].class)
                    .invoke(null, 0x8069, binding); // GL_TEXTURE_BINDING_2D
            try {
                gl11.getMethod("glBindTexture", int.class, int.class).invoke(null, 0x0DE1, texture);
                int[] format = new int[1];
                gl11.getMethod("glGetTexLevelParameteriv", int.class, int.class, int.class, int[].class)
                        .invoke(null, 0x0DE1, 0, 0x1003, format);
                return format[0];
            } finally {
                gl11.getMethod("glBindTexture", int.class, int.class).invoke(null, 0x0DE1, binding[0]);
            }
        } catch (Exception | LinkageError t) {
            return 0;
        }
    }

    public static int sceneWidth(int width) {
        if (width <= 0 || !LOADED || !packHasFinalPass || !AdapterProfile.supportsSceneScaling() || !AdapterProfile.hasOpenGlContext()) return width;
        try {
            float scale = NativeBridge.scale();
            if (scale <= 1.001f) return width;
            return scaledDimension(width, scale);
        } catch (Exception | LinkageError t) {
            warn("scale failed: " + t);
            return width;
        }
    }

    /** Height is kept independent so odd window dimensions do not accumulate rounding. */
    public static int sceneHeight(int height) {
        if (height <= 0 || !LOADED || !packHasFinalPass || !AdapterProfile.supportsSceneScaling() || !AdapterProfile.hasOpenGlContext()) return height;
        try {
            float scale = NativeBridge.scale();
            if (scale <= 1.001f) return height;
            return scaledDimension(height, scale);
        } catch (Exception | LinkageError t) {
            return height;
        }
    }

    static int scaledDimension(int dimension, float scale) {
        if (dimension <= 0 || !Float.isFinite(scale) || scale <= 1.001f) return dimension;
        return Math.min(dimension, Math.max(64, Math.round(dimension / scale)));
    }

    /** Forget camera/history inputs after a world, shader or pipeline change. */
    public static void invalidateCamera() {
        if (!LOADED) return;
        try {
            NativeBridge.invalidateCamera();
        } catch (Exception | LinkageError t) {
            warn("invalidate camera failed: " + t);
        }
    }

    /** Set the viewport back to the native EGL surface before Iris' final pass. */
    public static void resetNativeViewport() {
        if (!LOADED) return;
        try {
            NativeBridge.viewport();
        } catch (Exception | LinkageError t) {
            warn("native viewport failed: " + t);
        }
    }

    /** Called on the render thread immediately after Iris' final pass. */
    private static boolean submitLogged;
    private static String previousSubmitFailure;
    private static long nextSubmitWarning;

    private static void reportSubmitFailure(String failure) {
        long now = System.nanoTime();
        if (!failure.equals(previousSubmitFailure) || now >= nextSubmitWarning) {
            mark(failure + "; repeated failures limited to one report per 30 seconds");
            nextSubmitWarning = now + 30_000_000_000L;
        }
        previousSubmitFailure = failure;
    }

    public static void submit(Object finalColorFramebuffer) {
        if (!LOADED) {
            DiagnosticTrace.submitResult(false, null, null, "unavailable", null, false);
            return;
        }
        Integer rc = null;
        MainSubmissionTarget target = null;
        boolean attempted = false;
        String stage = "target_bind";
        Throwable failure = null;
        try {
            target = AdapterProfile.bindSubmissionTarget(finalColorFramebuffer);
            MainSubmissionTarget scope = target;
            try (scope) {
                stage = "native_submit";
                attempted = true;
                rc = NativeBridge.submit();
                stage = "target_restore";
            }
            stage = "complete";
            if (rc == 0) {
                previousSubmitFailure = null;
                if (!submitLogged) {
                    submitLogged = true;
                    mark("FSR3 submit success");
                }
            } else {
                reportSubmitFailure("submit rc=" + rc);
            }
        } catch (Exception | LinkageError t) {
            failure = t;
            reportSubmitFailure("nativeSubmit failed: " + t);
        } finally {
            DiagnosticTrace.submitResult(attempted, rc, target, stage, failure, true);
        }
    }
}
