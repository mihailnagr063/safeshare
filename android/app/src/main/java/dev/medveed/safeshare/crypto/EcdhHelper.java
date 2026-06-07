package dev.medveed.safeshare.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class EcdhHelper {

    private static final String ALGORITHM = "XDH";
    private static final String CURVE = "X25519";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private EcdhHelper() {}

    public static KeyPair generateIdentityKeyPair(String alias) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE);
        kpg.initialize(new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec(CURVE))
                .setUserAuthenticationRequired(false)
                .build());
        return kpg.generateKeyPair();
    }

    public static KeyPair generateEphemeralKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
        // do not initialize() because only X25519 is supported
        // source: org.conscrypt.OpenSSLXDHKeyPairGenerator.java
        return kpg.generateKeyPair();
    }

    public static byte[] computeSharedSecret(PrivateKey myPriv, PublicKey theirPub) throws NoSuchAlgorithmException, InvalidKeyException {
        KeyAgreement ka = KeyAgreement.getInstance(ALGORITHM);
        ka.init(myPriv);
        ka.doPhase(theirPub, true);
        return ka.generateSecret();
    }

    public static byte[] hkdf(byte[] sharedSecret) throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] salt = "SafeShare-v2".getBytes();
        byte[] info = "ssf1-key-r".getBytes();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(sharedSecret);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] okm = mac.doFinal();
        
        return Arrays.copyOf(okm, 20);
    }

    public static byte[] getRawPublicKey(PublicKey pub) {
        byte[] encoded = pub.getEncoded();
        if (encoded == null) {
            throw new IllegalArgumentException("PublicKey.getEncoded() returned null");
        }

        // sometimes
        if (encoded.length == 32) {
            return encoded;
        }

        // X.509 SPKI: 12b header + 32b key
        if (encoded.length >= 44) {
            return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        }
        throw new IllegalArgumentException("Unexpected public key encoding length: " + encoded.length);
    }

    public static PublicKey getPublicKeyFromRaw(byte[] raw) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] header = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00 };
        byte[] encoded = new byte[header.length + raw.length];
        System.arraycopy(header, 0, encoded, 0, header.length);
        System.arraycopy(raw, 0, encoded, header.length, raw.length);
        
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePublic(new X509EncodedKeySpec(encoded));
    }
}
