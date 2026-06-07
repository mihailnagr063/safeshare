package dev.medveed.safeshare.crypto;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TransferCodeV2 {

    private static final String SSHARE_PREFIX = "sshare://";

    public final String storagePrefix;
    public final String data;
    public final byte[] ephPub;
    public final String filename;

    public TransferCodeV2(String storagePrefix, String data, byte[] ephPub) {
        this(storagePrefix, data, ephPub, null);
    }

    public TransferCodeV2(String storagePrefix, String data, byte[] ephPub,
                          String filename) {
        if (storagePrefix == null || storagePrefix.isEmpty())
            throw new IllegalArgumentException("invalid storagePrefix");
        if (data == null) throw new IllegalArgumentException("invalid data");
        if (ephPub == null || ephPub.length != 32) throw new IllegalArgumentException("invalid ephPub");
        this.storagePrefix = storagePrefix;
        this.data = data;
        this.ephPub = ephPub;
        this.filename = filename;
    }

    public String format() {
        String b64Eph = Base64.getUrlEncoder().withoutPadding().encodeToString(ephPub);
        String b64Data = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(data.getBytes(StandardCharsets.UTF_8));
        if (filename != null && !filename.isEmpty()) {
            String b64Name = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(filename.getBytes(StandardCharsets.UTF_8));
            return storagePrefix + ":" + b64Data + "." + b64Name + "." + b64Eph;
        }
        return storagePrefix + ":" + b64Data + "." + b64Eph;
    }

    public String formatUri() {
        return SSHARE_PREFIX + format();
    }

    public static TransferCodeV2 parse(String s) {
        if (s.startsWith(SSHARE_PREFIX)) {
            s = s.substring(SSHARE_PREFIX.length());
        }
        int colon = s.indexOf(':');
        String prefix;
        String rest;
        if (colon > 0 && colon <= 2) {
            prefix = s.substring(0, colon);
            rest = s.substring(colon + 1);
        } else {
            prefix = "s";
            rest = s;
        }
        int lastDot = rest.lastIndexOf('.');
        if (lastDot < 0) throw new IllegalArgumentException("missing '.'");
        String b64Eph = rest.substring(lastDot + 1);
        byte[] ephPub = Base64.getUrlDecoder().decode(b64Eph);
        String middle = rest.substring(0, lastDot);

        int firstDot = middle.indexOf('.');
        String rawData;
        String b64NamePart = null;
        if (firstDot >= 0) {
            rawData = middle.substring(0, firstDot);
            b64NamePart = middle.substring(firstDot + 1);
        } else {
            rawData = middle;
        }

        String data;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawData);
            String decodedStr = new String(decoded, StandardCharsets.UTF_8);
            if ("s".equals(prefix) && !decodedStr.contains("|")) {
                data = rawData;
            } else {
                data = decodedStr;
            }
        } catch (Exception e) {
            data = rawData;
        }

        String filename = null;
        if (b64NamePart != null) {
            try {
                filename = new String(Base64.getUrlDecoder().decode(b64NamePart),
                        StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
        }

        return new TransferCodeV2(prefix, data, ephPub, filename);
    }

    @Nullable
    public static String extractSafeShareBaseUrl(String data) {
        if (data == null) return null;
        int pipe = data.indexOf('|');
        return pipe >= 0 ? data.substring(0, pipe) : null;
    }

    @Nullable
    public static String extractFileId(String prefix, String data) {
        if (data == null) return null;
        if ("s".equals(prefix)) {
            int pipe = data.indexOf('|');
            return pipe >= 0 ? data.substring(pipe + 1) : data;
        }
        return data;
    }
}
