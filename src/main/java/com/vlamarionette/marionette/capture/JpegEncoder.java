package com.vlamarionette.marionette.capture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * RGB pixels → bilinear scale → JPEG.
 *
 * <p>Scaling happens here, not at render time: the render target must stay at
 * the protocol's 640x360 size, while the model-facing JPEG is a separate
 * output specification. This method allocates and compresses and must only be
 * called on the encoder thread.
 */
public final class JpegEncoder {

    static {
        ImageIO.setUseCache(false);
    }

    private JpegEncoder() {
    }

    public static byte[] encode(byte[] rgb, int width, int height,
                                int outWidth, int outHeight, float quality) throws IOException {
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

        BufferedImage scaled = source;
        if (outWidth != width || outHeight != height) {
            scaled = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, outWidth, outHeight, null);
            } finally {
                graphics.dispose();
            }
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
             ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.0F, Math.min(1.0F, quality)));
            writer.setOutput(stream);
            writer.write(null, new IIOImage(scaled, null, null), param);
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
