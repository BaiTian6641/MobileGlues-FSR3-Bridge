package org.lwjgl.opengl;

/** Host fault-injection fixture; never included in a mod JAR. */
public final class GL11 {
    public static int texture = 17, scissor = 1, depthMask = 0;
    public static float clearDepth = 0.75f;
    public static float clearedDepth;
    public static boolean failQuery, failClear;
    public static int[] viewport = {3, 5, 900, 600};
    public static int textureWidth = 2880, textureHeight = 1800;
    public static void glGetIntegerv(int pname, int[] out) {
        if (pname == 0x0BA2) {
            System.arraycopy(viewport, 0, out, 0, 4);
            return;
        }
        out[0] = switch (pname) {
            case 0x8CA6 -> GL30.draw;
            case 0x8CAA -> GL30.read;
            case 0x8069 -> texture;
            case 0x0C11 -> scissor;
            case 0x0B72 -> depthMask;
            default -> throw new IllegalArgumentException("unhandled fixture state");
        };
    }
    public static void glGetFloatv(int pname, float[] out) { out[0] = clearDepth; }
    public static void glBindTexture(int target, int id) { texture = id; }
    public static void glGetTexLevelParameteriv(int target, int level, int pname, int[] out) {
        if (failQuery) throw new IllegalStateException("injected texture query failure");
        out[0] = switch (pname) {
            case 0x1000 -> textureWidth;
            case 0x1001 -> textureHeight;
            default -> 0x8CAC;
        };
    }
    public static void glViewport(int x, int y, int width, int height) {
        viewport = new int[]{x, y, width, height};
    }
    public static void glDisable(int capability) { scissor = 0; }
    public static void glEnable(int capability) { scissor = 1; }
    public static void glDepthMask(boolean value) { depthMask = value ? 1 : 0; }
    public static void glClearDepth(double value) { clearDepth = (float) value; }
    public static void glClear(int mask) {
        clearedDepth = clearDepth;
        if (failClear) throw new IllegalStateException("injected clear failure");
    }
}
