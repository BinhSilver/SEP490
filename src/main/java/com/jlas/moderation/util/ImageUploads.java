package com.jlas.moderation.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;

public final class ImageUploads {
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "webp", "image/webp", "gif", "image/gif");
    private ImageUploads() {}
    public static byte[] validate(MultipartFile file, long maxSize, boolean allowGif) {
        byte[] bytes = UploadFiles.read(file, maxSize);
        String extension = UploadFiles.extension(file.getOriginalFilename());
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!mime.equals(TYPES.get(extension)) || (!allowGif && "gif".equals(extension))
                || !hasSignature(bytes, extension)) invalid();
        if (!"webp".equals(extension)) {
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) invalid();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels <= 0 || pixels > 16_000_000L || reader.read(0) == null) invalid();
                } finally { reader.dispose(); }
            } catch (IOException | IllegalArgumentException e) { invalid(); }
        }
        return bytes;
    }
    public static boolean hasSignature(byte[] b, String extension) {
        if (b == null || b.length < 12) return false;
        return switch (extension) {
            case "jpg", "jpeg" -> (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255;
            case "png" -> (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71
                    && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10;
            case "gif" -> b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                    && (b[4] == '7' || b[4] == '9') && b[5] == 'a';
            case "webp" -> b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'
                    && b[12] == 'V' && b[13] == 'P' && b[14] == '8'
                    && (b[15] == ' ' || b[15] == 'L' || b[15] == 'X')
                    && Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(b, 4, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()) == b.length - 8L;
            default -> false;
        };
    }
    private static void invalid() {
        throw new ApiException(ErrorCode.INVALID_IMAGE, "Định dạng, MIME hoặc nội dung ảnh không hợp lệ");
    }
}
