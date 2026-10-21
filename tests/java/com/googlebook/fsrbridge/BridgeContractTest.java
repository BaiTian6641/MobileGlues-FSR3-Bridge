package com.googlebook.fsrbridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Executable host contracts, including GL state restoration after injected failures. */
public final class BridgeContractTest {
    public static final class Matrix {
        public void get(float[] target, int offset) {
            for (int i = 0; i < 16; i++) target[offset + i] = i + 0.25f;
        }
    }
    public static final class LegacyMatrix {
        public void method_4932(FloatBuffer target) { fill(target); }
        public void m_27650_(FloatBuffer target) { fill(target); }
        private void fill(FloatBuffer target) { for (int i = 0; i < 16; i++) target.put(i, i + 0.5f); }
    }
    public static final class Vector {
        public double x = 1, y = 2, z = 3;
        public double field_1352 = 1, field_1351 = 2, field_1350 = 3;
        public double f_82479_ = 1, f_82480_ = 2, f_82481_ = 3;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Object call(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = Fsr3Bridge.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
    public static void main(String[] args) throws Exception {
        check(NativeBridge.resourceAbi("aarch64").equals("arm64-v8a"), "ARM64 selection");
        check(NativeBridge.resourceAbi("amd64").equals("x86_64"), "x86_64 selection");
        try { NativeBridge.resourceAbi("mips"); throw new AssertionError("unknown ABI accepted"); }
        catch (IllegalArgumentException expected) { /* Reject unsupported CPU architectures. */ }
        check(Fsr3Bridge.scaledDimension(2880, 1.7f) == 1694, "Balanced width");
        check(Fsr3Bridge.scaledDimension(31, 3.0f) == 31, "tiny targets must never be enlarged");
        check(Fsr3Bridge.scaledDimension(101, Float.NaN) == 101, "invalid scale");
        check(!AdapterProfile.hasOpenGlContext(), "missing GL context must disable backend");
        float[] matrix = new float[16];
        AdapterProfile.copyMatrix(new Matrix(), matrix);
        check(matrix[12] == 12.25f, "JOML layout");
        AdapterProfile.copyMatrix(new LegacyMatrix(), matrix);
        check(matrix[12] == 12.5f, "legacy matrix layout and pinned method mapping");
        for (int i = 0; i < 3; i++) check(AdapterProfile.vectorComponent(new Vector(), i) == i + 1, "camera field mapping");
        GL11.failQuery = true;
        check((Integer) call("queryTextureFormat", new Class<?>[]{int.class}, 99) == 0, "query failure result");
        check(GL11.texture == 17, "texture binding restored after query failure");
        GL30.failAttachment = true;
        check((Integer) call("createDepthFbo", new Class<?>[]{int.class}, 99) == 0, "attachment failure result");
        check(GL30.draw == 31 && GL30.read == 37 && GL30.deleted == 50, "FBO bindings restored and rejected FBO deleted");
        var field = Fsr3Bridge.class.getDeclaredField("managedDepthFbo");
        field.setAccessible(true);
        field.setInt(null, 80);
        GL11.failClear = true;
        try { Fsr3Bridge.clearSceneDepth(); throw new AssertionError("clear failure hidden"); }
        catch (IllegalStateException expected) { /* A failed scene clear must stay observable. */ }
        check(GL30.draw == 31 && GL30.read == 37, "FBO bindings after clear failure");
        check(GL11.scissor == 1 && GL11.depthMask == 0 && GL11.clearDepth == 0.75f, "clear state after clear failure");
        System.out.println("PASS bridge host contracts " + AdapterProfile.value("id"));
    }
}
