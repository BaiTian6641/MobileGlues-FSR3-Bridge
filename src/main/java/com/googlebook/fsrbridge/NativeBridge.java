package com.googlebook.fsrbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

/** Loads only the bundled JNI helper; the helper binds an already mapped MobileGlues core. */
public final class NativeBridge {
    private static final long MAX_HELPER_BYTES = 4 * 1024 * 1024;
    private static boolean helperLoaded;
    private static boolean initialized;

    private NativeBridge() { }

    static String resourceAbi(String architecture) {
        return switch (architecture.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64-v8a";
            case "x86_64", "amd64" -> "x86_64";
            default -> throw new IllegalArgumentException("unsupported architecture " + architecture);
        };
    }

    static synchronized boolean load(String corePath) {
        if (initialized) return true;
        try {
            if (!helperLoaded) {
                loadHelper(resourceAbi(System.getProperty("os.arch", "")));
                helperLoaded = true;
            }
            initialized = init(corePath);
            return initialized;
        } catch (IOException | NoSuchAlgorithmException | LinkageError | RuntimeException failure) {
            Fsr3Bridge.mark("helper unavailable: " + failure);
            return false;
        }
    }

    private static void loadHelper(String abi) throws IOException, NoSuchAlgorithmException {
        Properties checksums = new Properties();
        try (InputStream stream = NativeBridge.class.getResourceAsStream("/native/checksums.properties")) {
            if (stream == null) throw new IOException("missing native checksum manifest");
            checksums.load(stream);
        }
        String expected = checksums.getProperty(abi);
        if (expected == null || !expected.matches("[0-9a-f]{64}")) throw new IOException("invalid native checksum");
        Path directory = Files.createTempDirectory("mgfsrhelper-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path library = directory.resolve("libmgfsrhelper.so");
        try {
            try (InputStream stream = NativeBridge.class.getResourceAsStream("/native/" + abi + "/libmgfsrhelper.so")) {
                if (stream == null) throw new IOException("missing native helper for " + abi);
                byte[] data = stream.readNBytes((int) MAX_HELPER_BYTES + 1);
                if (data.length > MAX_HELPER_BYTES) throw new IOException("native helper exceeds size limit");
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IOException("native helper checksum mismatch");
                }
                Files.write(library, data, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("r-x------"));
            System.load(library.toAbsolutePath().toString());
            helperLoaded = true; // Keep the loaded identity even if unlink reports a later I/O failure.
        } finally {
            // Android/Linux retain the mapped image after unlink; no executable temp files persist.
            Files.deleteIfExists(library);
            Files.deleteIfExists(directory);
        }
    }

    static native boolean init(String corePath);
    static native boolean available();
    static native float scale();
    static native void viewport();
    static native void camera(float[] modelView, float[] projection, double[] cameraPosition);
    static native void invalidateCamera();
    static native void bridgeMode();
    static native int submit();
    public static native void finalScope(boolean active);
    public static native void setSceneDepth(int texture);
    static native String trace(int op, int role, int frame, int event);
}
