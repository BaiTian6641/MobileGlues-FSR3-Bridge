package com.googlebook.fsrbridge;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.opengl.GlTexture;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import com.googlebook.fsrbridge.mixin.RenderTargetsTraceMixin;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.targets.RenderTargets;

/** Executes pinned Iris resize/getter bytecode with host-only texture/attachment ownership probes. */
public final class SceneDepthContractTest {
    private SceneDepthContractTest() {}

    private static final class Texture extends GpuTexture {
        private int closes;
        Texture() { super(5, "host ownership probe", null, 640, 360, 1, 1); }
        @Override public void close() { closes++; }
        @Override public boolean isClosed() { return closes != 0; }
    }

    public static final class Attachment extends GlFramebuffer {
        private GpuTexture depth;
        private boolean fail;
        private SnapshotHook hook;
        private boolean forwarding;
        @Override public boolean hasDepthAttachment() { return true; }
        @Override public void addDepthAttachment(GpuTexture texture) {
            if (hook != null && !forwarding) {
                forwarding = true;
                try { hook.attach(this, texture); }
                finally { forwarding = false; }
                return;
            }
            if (fail) throw new IllegalStateException("injected attachment failure");
            if (texture.isClosed()) throw new AssertionError("closed depth attached");
            depth = texture;
        }
    }

    /** Invokes the generated redirect handler; it does not claim a live Mixin transform. */
    public static final class SnapshotHook extends RenderTargetsTraceMixin {
        void attach(GlFramebuffer framebuffer, GpuTexture depth) {
            try {
                var handler = RenderTargetsTraceMixin.class.getDeclaredMethod(
                        "mgfsr3$preserveDepthSnapshot", GlFramebuffer.class, GpuTexture.class);
                handler.setAccessible(true);
                handler.invoke(this, framebuffer, depth);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new AssertionError(failure);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static Object uninitialized(Class<?> type) throws ReflectiveOperationException {
        // Skip GL-owning constructors, then execute the original resize/getter methods.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void retire(Texture texture) throws Exception {
        set(null, Fsr3Bridge.class, "managedDepth", texture);
        set(null, Fsr3Bridge.class, "managedDepthFbo", 0);
        var method = Fsr3Bridge.class.getDeclaredMethod("retireManagedDepth");
        method.setAccessible(true);
        method.invoke(null);
    }

    public static void main(String[] args) throws Exception {
        Texture first = new Texture(), second = new Texture(), main = new Texture();
        Attachment one = (Attachment) uninitialized(Attachment.class);
        Attachment two = (Attachment) uninitialized(Attachment.class);
        Attachment handDestination = (Attachment) uninitialized(Attachment.class);
        Attachment translucentDestination = (Attachment) uninitialized(Attachment.class);
        Texture hand = new Texture(), translucent = new Texture();
        handDestination.depth = hand;
        translucentDestination.depth = translucent;
        one.depth = two.depth = first;
        RenderTargets targets = (RenderTargets) uninitialized(RenderTargets.class);
        set(targets, RenderTargets.class, "currentDepthTexture", first);
        set(targets, RenderTargets.class, "cachedWidth", 640);
        set(targets, RenderTargets.class, "cachedHeight", 360);
        set(targets, RenderTargets.class, "cachedDepthBufferVersion", 7);
        set(targets, RenderTargets.class, "ownedFramebuffers",
                List.of(one, two, handDestination, translucentDestination));

        // Reproduce the upstream identity/version distinction before exercising the fix.
        check(!targets.resizeIfNeeded(7, second, 640, 360, null, null), "extent unchanged");
        check(targets.getDepthTexture() == first && one.depth == first,
                "Iris ignores replacement identity when its version is unchanged");
        targets.resizeIfNeeded(8, second, 640, 360, null, null);
        check(handDestination.depth == second && translucentDestination.depth == second,
                "unmodified generation loop overwrites both snapshot attachments");
        set(targets, RenderTargets.class, "currentDepthTexture", first);
        set(targets, RenderTargets.class, "cachedDepthBufferVersion", 7);
        one.depth = two.depth = first;
        handDestination.depth = hand;
        translucentDestination.depth = translucent;
        SnapshotHook hook = new SnapshotHook();
        set(hook, RenderTargetsTraceMixin.class, "noHand", hand);
        set(hook, RenderTargetsTraceMixin.class, "noTranslucents", translucent);
        set(hook, RenderTargetsTraceMixin.class, "noHandDestFb", handDestination);
        set(hook, RenderTargetsTraceMixin.class, "noTranslucentsDestFb", translucentDestination);
        one.hook = two.hook = handDestination.hook = translucentDestination.hook = hook;

        SceneDepthGeneration version = new SceneDepthGeneration();
        check(version.prepare(7, first) == 7, "constructor retains original initial version");
        version.complete();
        check(version.prepare(7, first) == 7, "unchanged first frame must not force reattachment");
        version.complete();
        retire(first);
        int replacement = version.prepare(7, second);
        check(replacement != 7 && !first.isClosed(), "retired depth remains alive before adoption");
        check(!targets.resizeIfNeeded(replacement, second, 640, 360, null, null),
                "identity adoption can succeed while resize returns false");
        check(targets.getDepthTexture() == second && one.depth == second && two.depth == second,
                "actual Iris cached getter and all attachments adopt replacement");
        check(handDestination.depth == hand && translucentDestination.depth == translucent,
                "generated redirect preserves independent snapshots on generation-only change");
        version.complete();
        Fsr3Bridge.completeSceneDepthUpdate();
        check(first.closes == 1 && !second.isClosed() && !main.isClosed(), "only retired owned depth closes");

        int sourceChange = version.prepare(99, second);
        check(sourceChange != replacement, "source generation change propagates with stable managed identity");
        targets.resizeIfNeeded(sourceChange, second, 640, 360, null, null);
        version.complete();
        retire(second);
        int failed = version.prepare(99, main);
        two.fail = true;
        try {
            targets.resizeIfNeeded(failed, main, 640, 360, null, null);
            throw new AssertionError("attachment failure was swallowed");
        } catch (IllegalStateException expected) {
            check(targets.getDepthTexture() == main && one.depth == main && two.depth == second,
                    "failure leaves mixed references; this is not transactional rollback");
        }
        check(!second.isClosed(), "failed resize must retain still-attached old depth");
        int retry = version.prepare(99, main);
        check(retry != failed, "retry forces reattachment despite already-updated cached version");
        two.fail = false;
        targets.resizeIfNeeded(retry, main, 640, 360, null, null);
        version.complete();
        Fsr3Bridge.completeSceneDepthUpdate();
        check(second.closes == 1 && one.depth == main && two.depth == main && !main.isClosed(),
                "successful NativeAA transition releases owned depth, never Minecraft's depth");

        Texture scaledAgain = new Texture();
        int enabled = version.prepare(99, scaledAgain);
        check(enabled != retry, "NativeAA to scaled identity changes generation");
        targets.resizeIfNeeded(enabled, scaledAgain, 640, 360, null, null);
        version.complete();
        Fsr3Bridge.completeSceneDepthUpdate();
        check(!main.isClosed(), "source main depth is not bridge-owned");
        Texture replacementSnapshot = new Texture();
        set(hook, RenderTargetsTraceMixin.class, "noHand", replacementSnapshot);
        handDestination.addDepthAttachment(scaledAgain);
        check(handDestination.depth == replacementSnapshot,
                "snapshot recreation uses current field rather than previous snapshot identity");
        check(translucentDestination.depth == translucent && !hand.isClosed(),
                "redirect neither replaces nor closes unrelated snapshot resources");

        SceneDepthGeneration wrap = new SceneDepthGeneration();
        wrap.prepare(Integer.MAX_VALUE, main);
        wrap.complete();
        check(wrap.prepare(Integer.MIN_VALUE, second) == Integer.MIN_VALUE, "generation wrap changes equality");
        retire(scaledAgain);
        Fsr3Bridge.destroySceneDepth();
        check(scaledAgain.closes == 1, "teardown releases pending depth even without an active resource");
        // Real Minecraft GlTexture identity must serialize as a JSON number,
        // not the former unquoted Object.toString() token.
        GlTexture diagnosticDepth = (GlTexture) uninitialized(GlTexture.class);
        set(diagnosticDepth, GlTexture.class, "id", 73);
        set(targets, RenderTargets.class, "currentDepthTexture", diagnosticDepth);
        set(targets, RenderTargets.class, "noHand", diagnosticDepth);
        set(targets, RenderTargets.class, "noTranslucents", diagnosticDepth);
        var metadata = DiagnosticTrace.class.getDeclaredMethod("copyMetadata", Object.class, boolean.class);
        metadata.setAccessible(true);
        String json = (String) metadata.invoke(null, targets, true);
        System.out.println("DEPTH_JSON " + json);
        check(json.contains("\"source_depth_texture\":73") && json.contains("\"noHand_texture\":73")
                && json.contains("\"noTranslucents_texture\":73"), "GpuTexture IDs must be numeric");
        System.out.println("PASS actual Iris depth identity/generation, false-return adoption, retry and deferred ownership");
    }
}
