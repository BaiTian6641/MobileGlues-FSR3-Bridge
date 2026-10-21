package com.googlebook.fsrbridge;

/** Unknown reflective return types must never become raw JSON tokens. */
public final class DiagnosticMetadataTest {
    public static final class IntegerDepth {
        public int cachedWidth = 640, cachedHeight = 360;
        public int getDepthTexture() { return 41; }
    }
    public static final class UnexpectedDepth {
        public Object cachedWidth = "0,\"injected\":true";
        public Object cachedHeight = Double.NaN;
        public Object getDepthTexture() { return this; }
        @Override public String toString() { return "0,\"injected\":true"; }
    }
    private DiagnosticMetadataTest() {}

    public static void main(String[] args) throws Exception {
        var metadata = DiagnosticTrace.class.getDeclaredMethod("copyMetadata", Object.class, boolean.class);
        metadata.setAccessible(true);
        String legacy = (String) metadata.invoke(null, new IntegerDepth(), false);
        if (!legacy.contains("\"source_depth_texture\":41") || !legacy.contains("\"width\":640")) {
            throw new AssertionError("legacy texture identity must remain numeric");
        }
        String unknown = (String) metadata.invoke(null, new UnexpectedDepth(), true);
        if (!unknown.contains("\"source_depth_texture\":null") || !unknown.contains("\"width\":null")
                || !unknown.contains("\"height\":null") || unknown.contains("injected") || unknown.contains("NaN")) {
            throw new AssertionError("unexpected reflective values were serialized as raw JSON");
        }
        System.out.println("PASS diagnostic numeric/null metadata and unexpected-type rejection");
    }
}
