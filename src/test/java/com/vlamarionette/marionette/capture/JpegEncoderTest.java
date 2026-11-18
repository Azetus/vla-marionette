package com.vlamarionette.marionette.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class JpegEncoderTest {

    @Test
    void scalesRgbToConfiguredJpeg() throws Exception {
        int width = CaptureSpec.RENDER_WIDTH;
        int height = CaptureSpec.RENDER_HEIGHT;
        byte[] rgb = new byte[width * height * 3];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rgb[i++] = (byte) (x * 255 / width);
                rgb[i++] = (byte) (y * 255 / height);
                rgb[i++] = (byte) ((x + y) & 0xFF);
            }
        }

        byte[] jpeg = JpegEncoder.encode(rgb, width, height,
                CaptureSpec.ENCODE_WIDTH, CaptureSpec.ENCODE_HEIGHT, CaptureSpec.JPEG_QUALITY);

        assertTrue(jpeg.length > 4);
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(image);
        assertEquals(CaptureSpec.ENCODE_WIDTH, image.getWidth());
        assertEquals(CaptureSpec.ENCODE_HEIGHT, image.getHeight());
    }
}
