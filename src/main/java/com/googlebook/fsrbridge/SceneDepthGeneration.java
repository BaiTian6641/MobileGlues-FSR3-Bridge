package com.googlebook.fsrbridge;

/** Tracks both Minecraft's source generation and the selected Iris depth identity. */
public final class SceneDepthGeneration {
    private boolean initialized;
    private boolean pending;
    private int sourceVersion;
    private int generation;
    private Object selectedDepth;

    private record LegacyDepth(int texture, long allocationVersion) {}

    /** Compare legacy GL names by value, including reuse by a different owned allocation. */
    public int prepare(int version, int texture, long allocationVersion) {
        Object identity = selectedDepth;
        if (!(identity instanceof LegacyDepth previous)
                || previous.texture() != texture || previous.allocationVersion() != allocationVersion) {
            identity = new LegacyDepth(texture, allocationVersion);
        }
        return prepare(version, identity);
    }

    public int prepare(int version, Object depth) {
        if (depth == null) throw new IllegalArgumentException("missing scene depth");
        if (!initialized) {
            generation = version;
            initialized = true;
        } else if (pending || sourceVersion != version || selectedDepth != depth) {
            // Equality is Iris' only version operation, so wrapping is safe.
            // Retry a throwing resize with a fresh version: its prior call may
            // have updated the cached texture before updating every attachment.
            generation++;
        }
        sourceVersion = version;
        selectedDepth = depth;
        pending = true;
        return generation;
    }

    /** Call only after Iris has returned normally, even when it reports no size change. */
    public void complete() {
        pending = false;
    }
}
