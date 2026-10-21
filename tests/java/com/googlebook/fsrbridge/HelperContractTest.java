package com.googlebook.fsrbridge;

/** Real host dynamic-linker/JNI tests against deliberately complete and incomplete ABI fixtures. */
public final class HelperContractTest {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        if (NativeBridge.init(args[2])) throw new AssertionError("unmapped core was loaded");
        System.load(args[1]);
        if (NativeBridge.init(args[1]) || NativeBridge.init(args[1])) throw new AssertionError("incomplete ABI was cached");
        System.load(args[2]);
        if (!NativeBridge.init(args[2])) throw new AssertionError("complete loaded core rejected");
        if (!NativeBridge.depthConvention(0) || NativeBridge.depthConvention(1)
                || NativeBridge.depthConvention(3) || NativeBridge.depthConvention(-1)) {
            throw new AssertionError("older core depth negotiation did not fail closed");
        }
        if (!NativeBridge.available() || NativeBridge.scale() != 1.75f || NativeBridge.submit() != 23) {
            throw new AssertionError("JNI dispatch did not reach mapped fixture");
        }
        DiagnosticUnavailableTest.verify();
        System.out.println("PASS native helper loaded-core-only and transactional ABI binding");
    }
}
