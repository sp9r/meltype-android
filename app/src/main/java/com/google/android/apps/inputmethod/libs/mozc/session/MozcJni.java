package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * JNI bridge matching the prebuilt libmozc.so native symbol signatures.
 */
public final class MozcJni {
    static {
        System.loadLibrary("mozc");
        if (!initialize()) {
            throw new IllegalStateException("Mozc JNI initialization failed");
        }
    }

    private MozcJni() {}

    public static native boolean initialize();

    public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);

    public static native byte[] evalCommand(byte[] command);

    public static native String getDataVersion();
}
