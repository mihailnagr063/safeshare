package dev.medveed.safeshare.crypto;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Bip39Assets {

    private Bip39Assets() { /* no instances */ }

    public static void init(Context context) throws IOException {
        List<String> words = new ArrayList<>(2048);
        try (InputStream in = context.getAssets().open("bip39_en.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.contains(" ")) {
                    words.add(line);
                }
            }
        }
        Bip39Codec.initWithList(words);
    }
}
