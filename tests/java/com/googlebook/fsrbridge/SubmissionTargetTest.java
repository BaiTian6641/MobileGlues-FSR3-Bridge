package com.googlebook.fsrbridge;

import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Host fault-injection contract; actual GL/main-target acceptance requires Minecraft. */
public final class SubmissionTargetTest {
    private SubmissionTargetTest() { }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void reset(int draw, int read) {
        GL30.draw = draw;
        GL30.read = read;
        GL30.status = 0x8CD5;
        GL30.attachmentTexture = 99;
        GL30.attachmentType = 0x1702;
        GL30.attachmentLevel = 0;
        GL30.failAttachment = false;
        GL11.failQuery = false;
        GL11.texture = 17;
        GL11.textureWidth = 2880;
        GL11.textureHeight = 1800;
        GL11.viewport = new int[]{3, 5, 900, 600};
    }

    private static void restored(int draw, int read) {
        check(GL30.draw == draw && GL30.read == read, "both framebuffer bindings restored");
        check(GL11.texture == 17, "texture binding restored");
        check(Arrays.equals(GL11.viewport, new int[]{3, 5, 900, 600}), "viewport restored");
    }

    private static void rejected() throws ReflectiveOperationException {
        try {
            MainSubmissionTarget unexpected = MainSubmissionTarget.bind(50, 99, 2880, 1800);
            unexpected.close();
            throw new AssertionError("invalid output accepted");
        } catch (IllegalStateException | java.lang.reflect.InvocationTargetException expected) {
            restored(31, 37);
        }
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        // Modern render-pass closure can leave zero or a scene swap FBO bound.
        for (int[] prior : new int[][]{{0, 0}, {31, 37}}) {
            reset(prior[0], prior[1]);
            try (MainSubmissionTarget target = MainSubmissionTarget.bind(50, 99, 2880, 1800)) {
                check(target.colorTexture() == 99 && target.width() == 2880 && target.height() == 1800,
                        "immutable main output identity");
                check(GL30.draw == 50 && GL30.read == prior[1], "only output draw binding changed");
                check(Arrays.equals(GL11.viewport, new int[]{0, 0, 2880, 1800}), "native target viewport");
            }
            restored(prior[0], prior[1]);
        }
        reset(31, 37);
        try (MainSubmissionTarget target = MainSubmissionTarget.bind(50, 99, 2880, 1800)) {
            check(target.width() == 2880, "submission scope entered");
            throw new IllegalStateException("injected native submission failure");
        } catch (IllegalStateException expected) {
            restored(31, 37);
        }
        reset(31, 37);
        GL30.attachmentTexture = 98;
        rejected();
        reset(31, 37);
        GL30.attachmentLevel = 1;
        rejected();
        reset(31, 37);
        GL30.attachmentType = 0x8D41;
        rejected();
        reset(31, 37);
        GL30.status = 0x8CD6;
        rejected();
        reset(31, 37);
        GL11.textureWidth = 1694;
        rejected();
        reset(31, 37);
        GL11.textureHeight = 1059;
        rejected();
        reset(31, 37);
        GL11.failQuery = true;
        rejected();
        reset(31, 37);
        GL30.failAttachment = true;
        rejected();
        reset(31, 37);
        MainSubmissionTarget target = MainSubmissionTarget.bind(50, 99, 2880, 1800);
        target.close();
        target.close();
        restored(31, 37);
        if (!AdapterProfile.value("minecraft").equals("26.2")) {
            AdapterProfile.bindSubmissionTarget(null).close();
            restored(31, 37);
        }
        System.out.println("PASS main submission target identity, extent, failure cleanup and state restoration");
    }
}
