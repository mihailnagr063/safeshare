package dev.medveed.safeshare.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Bip39Codec {

    private static volatile List<String> wordlist;

    private Bip39Codec() { /* no instances */ }

    public static synchronized void initWithList(List<String> words) {
        wordlist = Collections.unmodifiableList(new ArrayList<>(words));
    }

    public static List<String> words() {
        List<String> w = wordlist;
        if (w == null) {
            throw new IllegalStateException("Bip39Codec.initWithList() not called");
        }
        return w;
    }

    public static List<String> encode(byte[] key16, byte[] r4) {
        if (key16.length != 16) throw new IllegalArgumentException("key must be 16 bytes");
        if (r4.length != 4) throw new IllegalArgumentException("R must be 4 bytes");
        List<String> words = words();
        if (words.size() != 2048) {
            throw new IllegalStateException(
                    "bip39 wordlist must have 2048 entries, got " + words.size());
        }

        byte[] payload = new byte[20];
        System.arraycopy(key16, 0, payload, 0, 16);
        System.arraycopy(r4, 0, payload, 16, 4);
        byte[] digest = sha256(payload);

        byte[] buf = new byte[22];
        System.arraycopy(payload, 0, buf, 0, 20);
        buf[20] = digest[0];
        buf[21] = digest[1];

        List<String> out = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            int bit = i * 11;
            int byteIdx = bit / 8;
            int shift = bit % 8;
            int hi = buf[byteIdx] & 0xff;
            int mid = buf[byteIdx + 1] & 0xff;
            int lo = (byteIdx + 2) < buf.length ? (buf[byteIdx + 2] & 0xff) : 0;
            int combined = (hi << 16) | (mid << 8) | lo; // 24 bits
            int idx = (combined >>> (24 - shift - 11)) & 0x7ff;
            out.add(words.get(idx));
        }
        return out;
    }

    public static Decoded decode(List<String> phrase) {
        if (phrase.size() != 16) {
            throw new IllegalArgumentException("expected 16 words");
        }
        List<String> words = words();
        if (words.size() != 2048) {
            throw new IllegalStateException(
                    "bip39 wordlist must have 2048 entries, got " + words.size());
        }

        byte[] buf = new byte[22];
        for (int i = 0; i < 16; i++) {
            String w = phrase.get(i);
            int idx = indexCaseInsensitive(words, w);
            if (idx < 0) {
                throw new IllegalArgumentException("word not in wordlist: " + w);
            }
            int bit = i * 11;
            int byteIdx = bit / 8;
            int shift = bit % 8;
            int shiftLeft = 24 - 11 - shift;
            int hi = buf[byteIdx] & 0xff;
            int mid = buf[byteIdx + 1] & 0xff;
            int lo = (byteIdx + 2) < buf.length ? (buf[byteIdx + 2] & 0xff) : 0;
            int window = (hi << 16) | (mid << 8) | lo;
            int mask = 0x7ff << shiftLeft;
            window = (window & ~mask) | ((idx & 0x7ff) << shiftLeft);
            buf[byteIdx] = (byte) ((window >>> 16) & 0xff);
            buf[byteIdx + 1] = (byte) ((window >>> 8) & 0xff);
            if ((byteIdx + 2) < buf.length) {
                buf[byteIdx + 2] = (byte) (window & 0xff);
            }
        }

        byte[] key = Arrays.copyOfRange(buf, 0, 16);
        byte[] r = Arrays.copyOfRange(buf, 16, 20);
        byte[] checksumGot = Arrays.copyOfRange(buf, 20, 22);

        byte[] payload = new byte[20];
        System.arraycopy(key, 0, payload, 0, 16);
        System.arraycopy(r, 0, payload, 16, 4);
        byte[] digest = sha256(payload);
        if (digest[0] != checksumGot[0] || digest[1] != checksumGot[1]) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        return new Decoded(key, r);
    }

    public static final class Decoded {
        public final byte[] key;
        public final byte[] r;
        Decoded(byte[] key, byte[] r) { this.key = key; this.r = r; }
    }

    private static int indexCaseInsensitive(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(needle)) return i;
        }
        return -1;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 missing", e);
        }
    }
}
