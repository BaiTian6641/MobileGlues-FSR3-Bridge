package org.lwjgl.opengl;

/** Host-only context availability switch; it supplies no OpenGL implementation. */
public final class GL {
    private GL() { }
    public static boolean active = true;
    public static Object getCapabilities() { return active ? Boolean.TRUE : null; }
}
