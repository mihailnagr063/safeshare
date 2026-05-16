package dev.medveed.safeshare.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class StreamingAesGcm {

    public static final byte[] MAGIC = { 'S', 'S', 'F', '1' };
    public static final byte VERSION = 0x01;
    public static final int CHUNK_SIZE = 1 << 20; // 1 MiB
    public static final byte CHUNK_SIZE_LOG2 = 20;
    public static final int TAG_LEN = 16;
    public static final int HEADER_LEN = 24;
    public static final long NAME_NONCE_COUNTER = 0xFFFFFFFFFFFFFFFEL;

    private static final byte[] NAME_AAD = "SSF1:name".getBytes(StandardCharsets.US_ASCII);

    private StreamingAesGcm() { /* no instances */ }

    // ----- encrypt ----------------------------------------------------
    public static void encrypt(
            KeyMaterial km,
            String filename,
            long plaintextSize,
            InputStream in,
            OutputStream out
    ) throws IOException, GeneralSecurityException {
        if (plaintextSize <= 0) {
            throw new IllegalArgumentException("plaintextSize must be > 0");
        }
        byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 255) {
            throw new IllegalArgumentException("filename longer than 255 bytes");
        }

        long total = (plaintextSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too many chunks");
        }
        int totalChunks = (int) total;
        if (totalChunks == 0) {
            throw new IllegalArgumentException("totalChunks must be >= 1");
        }

        SecretKeySpec keySpec = new SecretKeySpec(km.key, "AES");

        // Header
        ByteBuffer hdr = ByteBuffer.allocate(HEADER_LEN);
        hdr.put(MAGIC);
        hdr.put(VERSION);
        hdr.put(CHUNK_SIZE_LOG2);
        hdr.put((byte) 0).put((byte) 0); // reserved
        hdr.putInt(totalChunks);
        hdr.putLong(plaintextSize);
        hdr.put(km.r);
        out.write(hdr.array());

        // Filename blob
        byte[] nameBlob = gcmEncrypt(keySpec, nonce(km.r, NAME_NONCE_COUNTER), NAME_AAD, nameBytes);
        if (nameBlob.length > 0xffff) {
            throw new IllegalArgumentException("encrypted filename too long");
        }
        out.write((nameBlob.length >>> 8) & 0xff);
        out.write(nameBlob.length & 0xff);
        out.write(nameBlob);

        // Chunks
        byte[] buffer = new byte[CHUNK_SIZE];
        long bytesRemaining = plaintextSize;
        for (int i = 0; i < totalChunks; i++) {
            boolean last = (i == totalChunks - 1);
            int want = last ? (int) bytesRemaining : CHUNK_SIZE;
            readFully(in, buffer, 0, want);

            byte[] aad = chunkAad(i, totalChunks, last);
            byte[] chunkCt = gcmEncryptSlice(keySpec, nonce(km.r, (long) i + 1L), aad,
                    buffer, 0, want);
            out.write(chunkCt);
            bytesRemaining -= want;
        }
        if (bytesRemaining != 0) {
            throw new IOException(
                    "plaintextSize mismatch with actual stream length");
        }
        out.flush();
    }

    // ----- decrypt ----------------------------------------------------
    public static String decrypt(
            byte[] key16,
            byte[] expectedR4OrNull,
            InputStream inRaw,
            OutputStream out
    ) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(inRaw);

        byte[] hdr = new byte[HEADER_LEN];
        in.readFully(hdr);
        if (hdr[0] != 'S' || hdr[1] != 'S' || hdr[2] != 'F' || hdr[3] != '1') {
            throw new IOException("bad magic");
        }
        if (hdr[4] != VERSION) throw new IOException("bad version");
        if (hdr[5] != CHUNK_SIZE_LOG2) throw new IOException("bad chunk_size_log2");

        int totalChunks = ByteBuffer.wrap(hdr, 8, 4).getInt();
        long plaintextSize = ByteBuffer.wrap(hdr, 12, 8).getLong();
        byte[] r = new byte[4];
        System.arraycopy(hdr, 20, r, 0, 4);

        if (expectedR4OrNull != null) {
            for (int i = 0; i < 4; i++) {
                if (expectedR4OrNull[i] != r[i]) {
                    throw new IOException("R mismatch between phrase and header");
                }
            }
        }
        if (plaintextSize <= 0) throw new IOException("plaintext_size zero");
        if (totalChunks <= 0) throw new IOException("total_chunks zero");
        long expectedChunks = (plaintextSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (expectedChunks != totalChunks) {
            throw new IOException("total_chunks vs plaintext_size mismatch");
        }

        SecretKeySpec keySpec = new SecretKeySpec(key16, "AES");

        // Filename
        int nameLen = in.readUnsignedShort();
        if (nameLen < TAG_LEN || nameLen > 255 + TAG_LEN) {
            throw new IOException("filename length out of range");
        }
        byte[] nameBlob = new byte[nameLen];
        in.readFully(nameBlob);
        byte[] nameBytes = gcmDecrypt(keySpec, nonce(r, NAME_NONCE_COUNTER), NAME_AAD, nameBlob);
        String filename = new String(nameBytes, StandardCharsets.UTF_8);

        // Chunks
        byte[] buffer = new byte[CHUNK_SIZE + TAG_LEN];
        long bytesRemaining = plaintextSize;
        for (int i = 0; i < totalChunks; i++) {
            boolean last = (i == totalChunks - 1);
            int want = last ? (int) bytesRemaining : CHUNK_SIZE;
            int total = want + TAG_LEN;
            readFully(in, buffer, 0, total);
            byte[] aad = chunkAad(i, totalChunks, last);
            byte[] pt = gcmDecryptSlice(keySpec, nonce(r, (long) i + 1L), aad,
                    buffer, 0, total);
            out.write(pt);
            bytesRemaining -= want;
        }

        int extra = in.read();
        if (extra != -1) {
            throw new IOException("trailing data after last chunk");
        }
        out.flush();
        return filename;
    }

    // ----- helpers ----------------------------------------------------
    static byte[] nonce(byte[] r4, long counter) {
        byte[] n = new byte[12];
        System.arraycopy(r4, 0, n, 0, 4);
        ByteBuffer.wrap(n, 4, 8).putLong(counter);
        return n;
    }

    static byte[] chunkAad(int index, int total, boolean last) {
        byte[] a = new byte[19];
        byte[] prefix = "SSF1:chunk".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, a, 0, 10);
        ByteBuffer.wrap(a, 10, 4).putInt(index);
        ByteBuffer.wrap(a, 14, 4).putInt(total);
        a[18] = last ? (byte) 0x01 : (byte) 0x00;
        return a;
    }

    private static byte[] gcmEncrypt(SecretKeySpec key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(plaintext);
    }

    private static byte[] gcmEncryptSlice(SecretKeySpec key, byte[] nonce, byte[] aad,
                                          byte[] buf, int off, int len)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(buf, off, len);
    }

    private static byte[] gcmDecrypt(SecretKeySpec key, byte[] nonce, byte[] aad, byte[] blob)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(blob);
    }

    private static byte[] gcmDecryptSlice(SecretKeySpec key, byte[] nonce, byte[] aad,
                                          byte[] buf, int off, int len)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(buf, off, len);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len)
            throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) throw new EOFException("unexpected EOF at " + (off + read));
            read += n;
        }
    }

    public static byte[] encryptToBytes(KeyMaterial km, String filename, byte[] plain)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encrypt(km, filename, plain.length, new java.io.ByteArrayInputStream(plain), out);
        return out.toByteArray();
    }

    public static DecryptResult decryptFromBytes(byte[] key16, byte[] rOrNull, byte[] cipher)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String name = decrypt(key16, rOrNull, new java.io.ByteArrayInputStream(cipher), out);
        return new DecryptResult(name, out.toByteArray());
    }

    public static final class DecryptResult {
        public final String filename;
        public final byte[] plaintext;
        DecryptResult(String f, byte[] p) { this.filename = f; this.plaintext = p; }
    }

    @SuppressWarnings("unused")
    private static void touch(DataOutputStream ignored) { }
}
