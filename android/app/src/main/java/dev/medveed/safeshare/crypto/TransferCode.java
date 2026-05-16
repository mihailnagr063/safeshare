package dev.medveed.safeshare.crypto;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class TransferCode {

    private static final String SSHARE_PREFIX = "sshare://";

    public final byte[] fileId;
    public final KeyMaterial km;

    public TransferCode(byte[] fileId, KeyMaterial km) {
        if (fileId.length != 5) throw new IllegalArgumentException("fileId must be 5 bytes");
        this.fileId = fileId;
        this.km = km;
    }

    public String format() {
        String idPart = CrockfordBase32.encode5(fileId);
        List<String> words = Bip39Codec.encode(km.key, km.r);
        return idPart + "." + String.join(" ", words);
    }

    public String formatCompact() {
        String idPart = CrockfordBase32.encode5(fileId);
        byte[] payload = new byte[20];
        System.arraycopy(km.key, 0, payload, 0, 16);
        System.arraycopy(km.r, 0, payload, 16, 4);
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return idPart + "." + b64;
    }

    public String formatSshareUri() {
        return SSHARE_PREFIX + formatCompact();
    }

    public static TransferCode parse(String s) {
        s = stripScheme(s);
        int dot = s.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("missing '.' in code");
        byte[] fileId = CrockfordBase32.decode5(s.substring(0, dot));
        String phrase = s.substring(dot + 1).trim();
        String[] parts = phrase.split("\\s+");
        Bip39Codec.Decoded decoded = Bip39Codec.decode(Arrays.asList(parts));
        return new TransferCode(fileId, new KeyMaterial(decoded.key, decoded.r));
    }

    public static TransferCode parseCompact(String s) {
        s = stripScheme(s);
        int dot = s.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("missing '.' in code");
        byte[] fileId = CrockfordBase32.decode5(s.substring(0, dot));
        String b64 = s.substring(dot + 1).trim();
        byte[] payload = Base64.getUrlDecoder().decode(padBase64(b64));
        if (payload.length != 20) {
            throw new IllegalArgumentException(
                    "compact payload must be 20 bytes, got " + payload.length);
        }
        byte[] key = Arrays.copyOfRange(payload, 0, 16);
        byte[] r = Arrays.copyOfRange(payload, 16, 20);
        return new TransferCode(fileId, new KeyMaterial(key, r));
    }

    public static TransferCode parseAny(String s) {
        s = stripScheme(s);
        int dot = s.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("missing '.' in code");
        String afterDot = s.substring(dot + 1);
        if (afterDot.contains(" ")) {
            return parse(s);
        }
        return parseCompact(s);
    }

    private static String stripScheme(String s) {
        s = s.trim();
        if (s.startsWith(SSHARE_PREFIX)) {
            return s.substring(SSHARE_PREFIX.length());
        }
        int idx = s.indexOf("?c=");
        if (idx >= 0) {
            return s.substring(idx + 3);
        }
        return s;
    }

    private static String padBase64(String s) {
        switch (s.length() % 4) {
            case 2: return s + "==";
            case 3: return s + "=";
            default: return s;
        }
    }
}
