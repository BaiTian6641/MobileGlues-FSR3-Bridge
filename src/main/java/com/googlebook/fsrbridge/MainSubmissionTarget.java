package com.googlebook.fsrbridge;

import java.lang.reflect.Method;

/**
 * Binds Iris' existing main-color framebuffer for one native submission.
 * The identity and extent come from Minecraft's main target, never from a
 * scene framebuffer that happened to remain bound after a swap pass.
 */
public final class MainSubmissionTarget implements AutoCloseable {
    private static final int DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int READ_FRAMEBUFFER = 0x8CA8;
    private static final int COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int TEXTURE_2D = 0x0DE1;
    private static final MainSubmissionTarget UNCHANGED = new MainSubmissionTarget();

    private final Method bindFramebuffer;
    private final Method viewport;
    private final int previousDraw;
    private final int previousRead;
    private final int[] previousViewport;
    private final int colorTexture;
    private final int width;
    private final int height;
    private boolean closed;

    private MainSubmissionTarget() {
        bindFramebuffer = viewport = null;
        previousDraw = previousRead = colorTexture = width = height = 0;
        previousViewport = null;
    }

    private MainSubmissionTarget(Method bind, Method setViewport, int draw, int read,
                                 int[] savedViewport, int texture, int targetWidth, int targetHeight) {
        bindFramebuffer = bind;
        viewport = setViewport;
        previousDraw = draw;
        previousRead = read;
        previousViewport = savedViewport;
        colorTexture = texture;
        width = targetWidth;
        height = targetHeight;
    }

    static MainSubmissionTarget unchanged() {
        return UNCHANGED;
    }

    static MainSubmissionTarget bind(int framebuffer, int texture, int width, int height)
            throws ReflectiveOperationException {
        if (framebuffer <= 0 || texture <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid main output identity or extent");
        }
        Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
        Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
        Method getInteger = gl11.getMethod("glGetIntegerv", int.class, int[].class);
        Method bind = gl30.getMethod("glBindFramebuffer", int.class, int.class);
        Method setViewport = gl11.getMethod("glViewport", int.class, int.class, int.class, int.class);
        Method attachment = gl30.getMethod("glGetFramebufferAttachmentParameteriv", int.class,
                int.class, int.class, int[].class);
        Method status = gl30.getMethod("glCheckFramebufferStatus", int.class);
        Method bindTexture = gl11.getMethod("glBindTexture", int.class, int.class);
        Method textureParameter = gl11.getMethod("glGetTexLevelParameteriv", int.class,
                int.class, int.class, int[].class);
        int[] savedViewport = new int[4];
        getInteger.invoke(null, 0x0BA2, savedViewport);
        int previousDraw = integer(getInteger, 0x8CA6);
        int previousRead = integer(getInteger, 0x8CAA);
        MainSubmissionTarget target = new MainSubmissionTarget(bind, setViewport, previousDraw,
                previousRead, savedViewport, texture, width, height);
        try {
            // Raw binds are scoped: Mojang's cached framebuffer names remain
            // unchanged and match the restored GL bindings when this closes.
            bind.invoke(null, DRAW_FRAMEBUFFER, framebuffer);
            if ((Integer) status.invoke(null, DRAW_FRAMEBUFFER) != 0x8CD5
                    || attachment(attachment, 0x8CD0) != 0x1702
                    || attachment(attachment, 0x8CD1) != texture
                    || attachment(attachment, 0x8CD2) != 0) {
                throw new IllegalStateException("Iris output framebuffer does not contain the current main color texture");
            }
            int previousTexture = integer(getInteger, 0x8069);
            try {
                bindTexture.invoke(null, TEXTURE_2D, texture);
                int[] size = new int[1];
                textureParameter.invoke(null, TEXTURE_2D, 0, 0x1000, size);
                if (size[0] != width) throw new IllegalStateException("main color GL width differs from target extent");
                textureParameter.invoke(null, TEXTURE_2D, 0, 0x1001, size);
                if (size[0] != height) throw new IllegalStateException("main color GL height differs from target extent");
            } finally {
                bindTexture.invoke(null, TEXTURE_2D, previousTexture);
            }
            setViewport.invoke(null, 0, 0, width, height);
            return target;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            try {
                target.close();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static int integer(Method query, int name) throws ReflectiveOperationException {
        int[] value = new int[1];
        query.invoke(null, name, value);
        return value[0];
    }

    private static int attachment(Method query, int name) throws ReflectiveOperationException {
        int[] value = new int[1];
        query.invoke(null, DRAW_FRAMEBUFFER, COLOR_ATTACHMENT0, name, value);
        return value[0];
    }

    public int colorTexture() { return colorTexture; }
    public int width() { return width; }
    public int height() { return height; }

    @Override
    public void close() {
        if (bindFramebuffer == null || closed) return;
        closed = true;
        try {
            try {
                bindFramebuffer.invoke(null, DRAW_FRAMEBUFFER, previousDraw);
            } finally {
                try {
                    bindFramebuffer.invoke(null, READ_FRAMEBUFFER, previousRead);
                } finally {
                    viewport.invoke(null, previousViewport[0], previousViewport[1],
                            previousViewport[2], previousViewport[3]);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot restore GL state after main output submission", failure);
        }
    }
}
