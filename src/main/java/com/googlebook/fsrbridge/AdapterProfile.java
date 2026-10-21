package com.googlebook.fsrbridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Optional;
import java.util.Properties;

/** Version-specific resource names, selected at build time from the audited artifact lock. */
public final class AdapterProfile {
    private static final Properties VALUES = read();

    private AdapterProfile() { }

    private static Properties read() {
        Properties properties = new Properties();
        try (InputStream stream = AdapterProfile.class.getResourceAsStream("/adapter.properties")) {
            if (stream == null) throw new IOException("missing adapter.properties");
            properties.load(stream);
            return properties;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static String value(String name) {
        String value = VALUES.getProperty(name);
        if (value == null) throw new IllegalStateException("missing adapter key " + name);
        return value;
    }

    public static String irisClass(String suffix) {
        return value("namespace") + "." + suffix;
    }

    public static boolean supportsSceneScaling() {
        return Boolean.parseBoolean(value("scene_scaling"));
    }

    public static void initialize() {
        // Deliberately avoid loading JNI here: loader entrypoints run before the GL context exists.
        System.err.println("[mgfsr3bridge] adapter=" + value("id") + "; GPU qualification pending");
    }

    /** Minecraft 26.2 can select Vulkan. A current LWJGL GL context is mandatory. */
    public static boolean hasOpenGlContext() {
        try {
            return Class.forName("org.lwjgl.opengl.GL").getMethod("getCapabilities").invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError failure) {
            return false;
        }
    }

    public static boolean hasFinalPass(Object programs) {
        try {
            Class<?> programId = Class.forName(value("ProgramId"));
            Object finalId = programId.getField("Final").get(null);
            return ((Optional<?>) programs.getClass().getMethod("get", programId).invoke(programs, finalId)).isPresent();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("pinned Iris final-pass API changed", failure);
        }
    }

    public static Object createDepth(int width, int height, int format, Object sourceGpu) throws ReflectiveOperationException {
        if (sourceGpu != null) {
            // Mirror the pinned 26.2 Iris allocation API while retaining the source's actual format and usage.
            Class<?> texture = Class.forName("com.mojang.blaze3d.textures.GpuTexture");
            Class<?> gpuFormat = Class.forName("com.mojang.blaze3d.GpuFormat");
            Object sourceFormat = texture.getMethod("getFormat").invoke(sourceGpu);
            int usage = gpuUsage(sourceGpu);
            Object device = Class.forName("com.mojang.blaze3d.systems.RenderSystem").getMethod("getDevice").invoke(null);
            return Class.forName("com.mojang.blaze3d.systems.GpuDevice")
                    .getMethod("createTexture", String.class, int.class, gpuFormat, int.class, int.class, int.class, int.class)
                    .invoke(device, "MobileGlues scene depth", usage, sourceFormat, width, height, 1, 1);
        }
        Class<?> depthFormat = Class.forName(irisClass("gl.texture.DepthBufferFormat"));
        Object value = depthFormat.getMethod("fromGlEnum", int.class).invoke(null, format);
        if (value == null) throw new IllegalArgumentException("unsupported depth format " + format);
        Class<?> depth = Class.forName(value("DepthTexture"));
        if (AdapterProfile.value("minecraft").equals("1.18.2")) {
            return depth.getConstructor(int.class, int.class, depthFormat).newInstance(width, height, value);
        }
        return depth.getConstructor(String.class, int.class, int.class, depthFormat)
                .newInstance("mgfsr3-scene-depth", width, height, value);
    }

    public static int gpuUsage(Object texture) throws ReflectiveOperationException {
        return (Integer) Class.forName("com.mojang.blaze3d.textures.GpuTexture").getMethod("usage").invoke(texture);
    }

    public static int textureId(Object depth) {
        try {
            if (value("minecraft").equals("26.2")) {
                Class<?> glTexture = Class.forName("com.mojang.blaze3d.opengl.GlTexture");
                if (!glTexture.isInstance(depth)) throw new IllegalArgumentException("FSR3 bridge requires the OpenGL backend");
                return (Integer) glTexture.getMethod("glId").invoke(depth);
            }
            return (Integer) depth.getClass().getMethod("getTextureId").invoke(depth);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("depth texture API changed", failure);
        }
    }

    public static void destroyDepth(Object depth) throws ReflectiveOperationException {
        if (value("minecraft").equals("26.2")) {
            Class.forName("com.mojang.blaze3d.textures.GpuTexture").getMethod("close").invoke(depth);
        } else {
            depth.getClass().getMethod("destroy").invoke(depth);
        }
    }

    public static void copyMatrix(Object matrix, float[] destination) throws ReflectiveOperationException {
        if (matrix == null) throw new IllegalArgumentException("null camera matrix");
        try {
            matrix.getClass().getMethod("get", float[].class, int.class).invoke(matrix, destination, 0);
        } catch (NoSuchMethodException legacyMatrix) {
            // 1.18.2 uses Mojang Matrix4f. The profile pins its runtime store mapping.
            FloatBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
            Method store = matrix.getClass().getMethod(value("matrix_store"), FloatBuffer.class);
            store.invoke(matrix, buffer);
            buffer.position(0);
            buffer.get(destination);
        }
    }

    public static double vectorComponent(Object vector, int component) throws ReflectiveOperationException {
        String[] fields = value("camera_fields").split(",");
        return vector.getClass().getField(fields[component]).getDouble(vector);
    }
}
