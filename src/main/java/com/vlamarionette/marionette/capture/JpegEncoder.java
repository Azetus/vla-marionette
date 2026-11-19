package com.vlamarionette.marionette.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * RGB pixels → JPEG.
 *
 * <p>The capture path encodes at the render size; window, render target and JPEG
 * are unified by {@link CaptureSpec}, so no scaling occurs.
 *
 * <p>This method allocates and compresses and must only be called on the encoder
 * thread.
 */
public final class JpegEncoder {

    static {
        ImageIO.setUseCache(false);
    }

    private JpegEncoder() {
    }

    public static byte[] encode(byte[] rgb, int width, int height, float quality) throws IOException {
        if (rgb.length < width * height * 3) {
            throw new IOException("rgb buffer too small: " + rgb.length);
        }

        int[] argb = new int[width * height];
        int in = 0;
        for (int i = 0; i < argb.length; i++) {
            int r = rgb[in++] & 0xFF;
            int g = rgb[in++] & 0xFF;
            int b = rgb[in++] & 0xFF;
            argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, width, height, argb, 0, width);

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
             ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.0F, Math.min(1.0F, quality)));
            writer.setOutput(stream);
            writer.write(null, new IIOImage(source, null, null), param);
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
