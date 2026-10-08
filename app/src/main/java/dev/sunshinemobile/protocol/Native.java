package dev.sunshinemobile.protocol;

/** ENet and libopus are the actual upstream libraries, not protocol approximations. */
public final class Native {
    static { System.loadLibrary("sunshine_transport"); }
    private Native() {}
    public static native long controlCreate(String ip,int port,int connectData);
    public static native byte[] controlPoll(long handle,int timeoutMs);
    public static native void controlDestroy(long handle);
    public static native long opusCreate(int samples);
    public static native byte[] opusEncode(long handle,short[] pcm,int samples);
    public static native void opusDestroy(long handle);
    public static native byte[][] parity(byte[][] data,int count,boolean audio);
}
