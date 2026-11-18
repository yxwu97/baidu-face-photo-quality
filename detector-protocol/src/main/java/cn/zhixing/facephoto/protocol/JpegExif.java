package cn.zhixing.facephoto.protocol;

/** Reads the primary image orientation from a JPEG APP1 Exif segment. */
public final class JpegExif {
    private JpegExif() {}

    public static int orientation(byte[] jpeg) {
        if (jpeg.length < 4 || unsigned(jpeg, 0) != 255 || unsigned(jpeg, 1) != 216) {
            return 1;
        }
        for (int position = 2; position < jpeg.length - 1;) {
            if (unsigned(jpeg, position++) != 255) break;
            while (position < jpeg.length && unsigned(jpeg, position) == 255) position++;
            if (position >= jpeg.length) break;
            int marker = unsigned(jpeg, position++);
            if (marker == 218 || marker == 217) break;
            if (marker == 1 || marker >= 208 && marker <= 215) continue;
            if (position > jpeg.length - 2) break;
            int length = unsigned(jpeg, position) * 256 + unsigned(jpeg, position + 1);
            if (length < 2 || length > jpeg.length - position) break;
            if (marker == 225 && length >= 16 && hasExifHeader(jpeg, position + 2)) {
                return tiffOrientation(jpeg, position + 8, position + length);
            }
            position += length;
        }
        return 1;
    }

    private static boolean hasExifHeader(byte[] data, int pos) {
        return data[pos] == 'E' && data[pos + 1] == 'x' && data[pos + 2] == 'i'
                && data[pos + 3] == 'f' && data[pos + 4] == 0 && data[pos + 5] == 0;
    }

    private static int tiffOrientation(byte[] data, int base, int end) {
        boolean little = data[base] == 'I' && data[base + 1] == 'I';
        boolean big = data[base] == 'M' && data[base + 1] == 'M';
        if ((!little && !big) || number(data, base + 2, 2, little) != 42) return 1;
        long offset = number(data, base + 4, 4, little);
        if (offset < 8 || offset > end - base - 2) return 1;
        int start = base + (int) offset;
        int count = (int) number(data, start, 2, little);
        if (count > (end - start - 2) / 12) return 1;
        for (int i = 0, pos = start + 2; i < count; i++, pos += 12) {
            if (number(data, pos, 2, little) == 0x112
                    && number(data, pos + 2, 2, little) == 3
                    && number(data, pos + 4, 4, little) == 1) {
                int value = (int) number(data, pos + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static long number(byte[] data, int start, int size, boolean little) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = value * 256 + unsigned(data, start + (little ? size - 1 - i : i));
        }
        return value;
    }

    private static int unsigned(byte[] data, int position) {
        return Byte.toUnsignedInt(data[position]);
    }
}
