package dev.medveed.safeshare.crypto;

import java.security.SecureRandom;

public final class KeyMaterial {

    public final byte[] key;
    public final byte[] r;

    public KeyMaterial(byte[] key, byte[] r) {
        if (key.length != 16) throw new IllegalArgumentException("key must be 16 bytes");
        if (r.length != 4) throw new IllegalArgumentException("R must be 4 bytes");
        this.key = key;
        this.r = r;
    }

    public static KeyMaterial fromHkdf(byte[] okm20) {
        if (okm20.length != 20) throw new IllegalArgumentException("OKM must be 20 bytes");
        byte[] k = new byte[16];
        byte[] r = new byte[4];
        System.arraycopy(okm20, 0, k, 0, 16);
        System.arraycopy(okm20, 16, r, 0, 4);
        return new KeyMaterial(k, r);
    }
}
