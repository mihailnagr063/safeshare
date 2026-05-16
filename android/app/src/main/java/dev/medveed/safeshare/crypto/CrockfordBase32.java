package dev.medveed.safeshare.crypto;

public final class CrockfordBase32 {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private CrockfordBase32() { }

    public static String encode5(byte[] bytes) {
        if (bytes.length != 5) {
            throw new IllegalArgumentException("expected 5 bytes");
        }
        long v = 0L;
        for (int i = 0; i < 5; i++) {
            v = (v << 8) | (bytes[i] & 0xffL);
        }
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v & 0x1fL));
            v >>>= 5;
        }
        return new String(out);
    }

    public static byte[] decode5(String s) {
        if (s.length() != 8) {
            throw new IllegalArgumentException("expected 8 characters");
        }
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            int digit;
            switch (c) {
                case '0': case 'O': digit = 0; break;
                case '1': case 'I': case 'L': digit = 1; break;
                case '2': digit = 2; break;
                case '3': digit = 3; break;
                case '4': digit = 4; break;
                case '5': digit = 5; break;
                case '6': digit = 6; break;
                case '7': digit = 7; break;
                case '8': digit = 8; break;
                case '9': digit = 9; break;
                case 'A': digit = 10; break;
                case 'B': digit = 11; break;
                case 'C': digit = 12; break;
                case 'D': digit = 13; break;
                case 'E': digit = 14; break;
                case 'F': digit = 15; break;
                case 'G': digit = 16; break;
                case 'H': digit = 17; break;
                case 'J': digit = 18; break;
                case 'K': digit = 19; break;
                case 'M': digit = 20; break;
                case 'N': digit = 21; break;
                case 'P': digit = 22; break;
                case 'Q': digit = 23; break;
                case 'R': digit = 24; break;
                case 'S': digit = 25; break;
                case 'T': digit = 26; break;
                case 'V': digit = 27; break;
                case 'W': digit = 28; break;
                case 'X': digit = 29; break;
                case 'Y': digit = 30; break;
                case 'Z': digit = 31; break;
                default:
                    throw new IllegalArgumentException("invalid character: " + c);
            }
            v = (v << 5) | digit;
        }
        if ((v >>> 40) != 0L) {
            throw new IllegalArgumentException("overflow");
        }
        byte[] out = new byte[5];
        for (int i = 4; i >= 0; i--) {
            out[i] = (byte) (v & 0xffL);
            v >>>= 8;
        }
        return out;
    }
}
