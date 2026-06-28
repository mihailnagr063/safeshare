package dev.medveed.safeshare.util;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

public final class QrUtil {

    private QrUtil() {}

    @Nullable
    public static Bitmap renderQr(@Nullable String content) {
        return renderQr(content, 800);
    }

    @Nullable
    public static Bitmap renderQr(@Nullable String content, int size) {
        if (content == null || content.isEmpty()) return null;
        try {
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.QR_CODE, size, size);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[off + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }
}
