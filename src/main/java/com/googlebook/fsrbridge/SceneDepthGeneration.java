package com.googlebook.fsrbridge;

/** Tracks both Minecraft's source generation and the selected Iris depth identity. */
public final class SceneDepthGeneration {
    private boolean initialized;
    private boolean pending;
    private int sourceVersion;
    private int generation;
    private Object selectedDepth;

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
