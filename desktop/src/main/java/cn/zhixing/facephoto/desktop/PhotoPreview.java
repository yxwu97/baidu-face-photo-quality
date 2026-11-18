package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.PhotoSource;
import cn.zhixing.facephoto.protocol.JpegExif;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PhotoPreview {
    record Loaded(Image image, boolean matchesRevision) {}

    private PhotoPreview() {}

    static Loaded load(Path root, String relative, String expectedHash, int targetPixels)
            throws IOException {
        byte[] bytes = Files.readAllBytes(PhotoSource.resolve(root, relative));
        int direction = JpegExif.orientation(bytes);
        boolean unchanged = expectedHash != null && expectedHash.equals(sha256(bytes));
        return new Loaded(transform(decode(bytes, targetPixels), direction), unchanged);
    }

    private static BufferedImage decode(byte[] bytes, int target) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("照片无法解码");
            ImageReader reader = readers.next();
            try {
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) throw new IOException("照片格式无效");
                reader.setInput(stream, true, true);
                int extent = Math.max(reader.getWidth(0), reader.getHeight(0));
                int stride = Math.max(1, Math.ceilDiv(extent, target));
                var options = reader.getDefaultReadParam();
                options.setSourceSubsampling(stride, stride, 0, 0);
                return reader.read(0, options);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Image transform(BufferedImage input, int direction) {
        int width = input.getWidth();
        int height = input.getHeight();
        boolean swap = direction >= 5;
        int targetWidth = swap ? height : width;
        int targetHeight = swap ? width : height;
        int[] original = input.getRGB(0, 0, width, height, null, 0, width);
        int[] normalized = new int[original.length];
        boolean mirrorX = direction == 2 || direction == 3 || direction == 6 || direction == 7;
        boolean mirrorY = direction == 3 || direction == 4 || direction == 7 || direction == 8;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = swap ? y : x;
                int dy = swap ? x : y;
                if (mirrorX) dx = targetWidth - 1 - dx;
                if (mirrorY) dy = targetHeight - 1 - dy;
                normalized[dy * targetWidth + dx] = original[y * width + x];
            }
        }
        WritableImage result = new WritableImage(targetWidth, targetHeight);
        result.getPixelWriter().setPixels(0, 0, targetWidth, targetHeight,
                PixelFormat.getIntArgbInstance(), normalized, 0, targetWidth);
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
