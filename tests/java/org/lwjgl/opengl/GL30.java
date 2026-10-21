package org.lwjgl.opengl;

/** Host fault-injection fixture; never included in a mod JAR. */
public final class GL30 {
    public static int draw = 31, read = 37, deleted;
    public static boolean failAttachment;
    public static int status = 0x8CD5, attachmentTexture = 99, attachmentLevel;
    public static int attachmentType = 0x1702;
    public static int glGenFramebuffers() { return 50; }
    public static void glBindFramebuffer(int target, int id) {
        if (target == 0x8CA9) draw = id; else read = id;
    }
    public static void glFramebufferTexture2D(int target, int attachment, int type, int texture, int level) {
        if (failAttachment) throw new IllegalStateException("injected framebuffer attachment failure");
    }
    public static int glCheckFramebufferStatus(int target) { return status; }
    public static void glGetFramebufferAttachmentParameteriv(int target, int attachment, int name, int[] out) {
        if (failAttachment) throw new IllegalStateException("injected attachment query failure");
        out[0] = switch (name) {
            case 0x8CD0 -> attachmentType;
            case 0x8CD1 -> attachmentTexture;
            case 0x8CD2 -> attachmentLevel;
            default -> throw new IllegalArgumentException("unhandled attachment query");
        };
    }
    public static void glDeleteFramebuffers(int id) { deleted = id; }
}
