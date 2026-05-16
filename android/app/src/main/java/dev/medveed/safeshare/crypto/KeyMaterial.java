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

    public static KeyMaterial generate() {
        SecureRandom rng = new SecureRandom();
        byte[] k = new byte[16];
        byte[] r = new byte[4];
        rng.nextBytes(k);
        rng.nextBytes(r);
        return new KeyMaterial(k, r);
    }
}
