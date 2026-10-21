package com.googlebook.fsrbridge;

/** Optional depth ABI negotiation against a loaded native core with the new symbol. */
public final class DepthConventionContractTest {
    private DepthConventionContractTest() {}

    public static void main(String[] args) {
        System.load(args[0]);
        if (NativeBridge.depthConvention(0)) throw new AssertionError("unbound renderer accepted");
        System.load(args[1]);
        if (!NativeBridge.init(args[1]) || !NativeBridge.depthConvention(0)
                || !NativeBridge.depthConvention(1) || NativeBridge.depthConvention(2)
                || NativeBridge.depthConvention(-1)) {
            throw new AssertionError("sampled depth convention negotiation failed");
        }
        System.out.println("PASS native sampled-depth negotiation: forward/reversed accepted, unknown rejected");
    }
}
