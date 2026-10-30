package com.jlas.game.service;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.util.AvatarFrameCatalog.Frame;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Gói khung avatar (.zip): ảnh khung (PNG hoặc WebP) và {@code manifest.json} ghi vị trí các hiệu ứng riêng của khung,
 * cùng định dạng với 4 khung gốc:
 * <pre>{ "image": "frame.webp", "gems": { "big": [[x, y]…], "gems": […], "orbs": […], "drips": […] } }</pre>
 * Toạ độ tính theo % khung (0–100). {@code big}: đá lớn, {@code gems}: đá nhỏ (cả hai chớp sáng khi vệt sáng chạy qua),
 * {@code orbs}: quả cầu xoay, {@code drips}: giọt treo. Hào quang, vệt sáng, kim tuyến, hạt bay đi theo chủ đề.
 */
@Service
public class AvatarFramePackService {
    static final long MAX_ZIP_BYTES = 10L * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIZE = 2048;
    static final int MAX_POINTS = 24;
    static final List<String> GROUPS = List.of("big", "gems", "orbs", "drips");
    /** Alpha tối đa coi là trong suốt (0–255). */
    private static final int TRANSPARENT = 16;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path directory;

    public AvatarFramePackService(@Value("${avatar-frames.dir:uploads/avatar-frames}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Ảnh khung đã lưu, toạ độ hiệu ứng (JSON, null nếu gói không ghi) và mô tả ngắn. */
    public record FramePack(String image, @JsonRawValue String gems, String summary) {
    }

    // ── Tải lên ──────────────────────────────────────────────────────────────

    public FramePack upload(byte[] zip) {
        if (zip == null || zip.length == 0) throw invalid("Chọn tệp .zip của gói khung");
        if (zip.length > MAX_ZIP_BYTES) throw invalid("Gói khung tối đa 10 MB");
        Map<String, byte[]> files = unzip(zip);
        JsonNode manifest;
        try {
            manifest = JSON.readTree(files.get("manifest.json"));
        } catch (IOException ex) {
            throw invalid("manifest.json không phải JSON hợp lệ");
        }
        if (manifest == null || !manifest.isObject()) throw invalid("manifest.json phải là một đối tượng JSON");
        String imageName = manifest.path("image").asText("");
        byte[] image = files.get(imageName);
        if (imageName.isBlank() || image == null) throw invalid("Không tìm thấy ảnh khung \"" + imageName + "\" trong gói");
        String extension = validateImage(image);
        String gems = manifest.hasNonNull("gems") ? normalizeGems(manifest.get("gems").toString()) : null;
        String url = save(image, extension);
        int points = gems == null ? 0 : countPoints(gems);
        return new FramePack(url, gems, "Ảnh " + extension.toUpperCase(Locale.ROOT)
                + (points > 0 ? ", " + points + " điểm hiệu ứng riêng" : ", chỉ hiệu ứng theo chủ đề"));
    }

    /** Chỉ ảnh khung (không có hiệu ứng riêng). */
    public String uploadImage(byte[] image) {
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) throw invalid("Ảnh khung tối đa 5 MB");
        return save(image, validateImage(image));
    }

    private String save(byte[] image, String extension) {
        try {
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + "." + extension;
            Files.write(directory.resolve(name), image);
            return "/uploads/avatar-frames/" + name;
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không lưu được ảnh. Hãy thử lại.");
        }
    }

    // ── Gói mẫu ──────────────────────────────────────────────────────────────

    /** Zip của một khung có sẵn (ảnh + vị trí hiệu ứng), để làm khung mới theo đúng định dạng. */
    public byte[] template(Frame frame) {
        String image = frame.image();
        String extension = image.substring(image.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = readImage(image);
            ObjectNode manifest = JSON.createObjectNode();
            manifest.put("image", "frame." + extension);
            manifest.set("gems", frame.gemsJson() == null ? JSON.createObjectNode() : JSON.readTree(frame.gemsJson()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("frame." + extension));
                zip.write(bytes);
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không tạo được gói mẫu");
        }
    }

    private byte[] readImage(String url) throws IOException {
        if (url.startsWith("/uploads/avatar-frames/")) {
            Path file = directory.resolve(url.substring("/uploads/avatar-frames/".length())).normalize();
            if (!file.startsWith(directory)) throw new IOException("Invalid path");
            return Files.readAllBytes(file);
        }
        try (InputStream in = new ClassPathResource("static" + url).getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── Toạ độ hiệu ứng ──────────────────────────────────────────────────────

    /**
     * Kiểm tra và chuẩn hoá toạ độ hiệu ứng ({big, gems, orbs, drips}: mảng [x, y] theo % khung). Rỗng → null.
     * Trả về JSON gọn để lưu vào avatar_frame.gems_json.
     */
    public static String normalizeGems(String json) {
        if (json == null || json.isBlank()) return null;
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException ex) {
            throw invalid("Vị trí hiệu ứng không phải JSON hợp lệ");
        }
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw invalid("gems phải là đối tượng {big, gems, orbs, drips}");
        ObjectNode clean = JSON.createObjectNode();
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!GROUPS.contains(name)) throw invalid("gems chỉ nhận big, gems, orbs, drips (có \"" + name + "\")");
            JsonNode points = node.get(name);
            if (!points.isArray()) throw invalid("gems." + name + " phải là mảng các điểm [x, y]");
            if (points.size() > MAX_POINTS) throw invalid("gems." + name + " tối đa " + MAX_POINTS + " điểm");
            ArrayNode out = clean.putArray(name);
            for (JsonNode point : points) {
                if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    throw invalid("Mỗi điểm trong gems." + name + " là [x, y]");
                }
                double x = point.get(0).asDouble(), y = point.get(1).asDouble();
                if (x < 0 || x > 100 || y < 0 || y > 100) throw invalid("Toạ độ trong gems." + name + " phải từ 0 đến 100 (% khung)");
                out.addArray().add(x).add(y);
            }
        }
        return clean.isEmpty() ? null : clean.toString();
    }

    private static int countPoints(String gems) {
        try {
            int total = 0;
            for (JsonNode group : JSON.readTree(gems)) total += group.size();
            return total;
        } catch (IOException ex) {
            return 0;
        }
    }

    // ── Ảnh khung ────────────────────────────────────────────────────────────

    /** PNG: kiểm tra đầy đủ (vuông, nền và lỗ giữa trong suốt). WebP: chữ ký và kích thước (Java không giải mã WebP). */
    public static String validateImage(byte[] bytes) {
        if (bytes.length > MAX_IMAGE_BYTES) throw invalid("Ảnh khung tối đa 5 MB");
        if (isWebp(bytes)) {
            int[] size = webpSize(bytes);
            checkSize(size[0], size[1]);
            return "webp";
        }
        try {
            validatePng(bytes);
        } catch (IOException ex) {
            throw invalid("Ảnh khung phải là PNG hoặc WebP");
        }
        return "png";
    }

    public static void validatePng(byte[] bytes) throws IOException {
        BufferedImage image;
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("Ảnh khung phải là PNG hoặc WebP");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    throw invalid("Ảnh khung phải là PNG hoặc WebP (cần nền trong suốt)");
                }
                checkSize(reader.getWidth(0), reader.getHeight(0));
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) throw new IOException("Invalid image");
        if (!image.getColorModel().hasAlpha()) {
            throw invalid("Ảnh khung cần nền trong suốt (PNG có kênh alpha)");
        }
        // Góc ảnh là nền, giữa ảnh là lỗ để lộ avatar: cả năm điểm phải trong suốt.
        int last = image.getWidth() - 1, middle = image.getWidth() / 2;
        int[][] points = {{0, 0}, {last, 0}, {0, last}, {last, last}, {middle, middle}};
        for (int[] point : points) {
            if ((image.getRGB(point[0], point[1]) >>> 24) > TRANSPARENT) {
                throw invalid("Nền và lỗ tròn ở giữa khung phải trong suốt để lộ avatar");
            }
        }
    }

    private static void checkSize(int width, int height) {
        if (width > MAX_SIZE || height > MAX_SIZE) throw invalid("Kích thước ảnh tối đa 2048 × 2048");
        if (width != height) throw invalid("Ảnh khung phải là hình vuông");
    }

    static boolean isWebp(byte[] b) {
        return b.length >= 30 && new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    /** Kích thước WebP đọc từ phần đầu tệp (VP8X, VP8L hoặc VP8). */
    static int[] webpSize(byte[] b) {
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X":
                return new int[]{1 + u24(b, 24), 1 + u24(b, 27)};
            case "VP8L":
                if ((b[20] & 0xFF) != 0x2F) break;
                int width = 1 + (((b[22] & 0x3F) << 8) | (b[21] & 0xFF));
                int height = 1 + (((b[24] & 0x0F) << 10) | ((b[23] & 0xFF) << 2) | ((b[22] & 0xC0) >> 6));
                return new int[]{width, height};
            case "VP8 ":
                if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) break;
                return new int[]{((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF, ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF};
            default:
                break;
        }
        throw invalid("Ảnh WebP không hợp lệ");
    }

    private static int u24(byte[] b, int offset) {
        return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF) << 16;
    }

    // ── Giải nén ─────────────────────────────────────────────────────────────

    /** manifest.json + một ảnh .png/.webp; bỏ thư mục bọc ngoài nếu có, chặn đường dẫn độc hại và tệp lạ. */
    static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > 16) throw invalid("Gói khung có quá nhiều tệp");
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store") || name.endsWith("Thumbs.db")) continue;
                if (name.contains("\\") || name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw invalid("Tên tệp không hợp lệ trong gói: " + name);
                }
                byte[] bytes = in.readNBytes(MAX_IMAGE_BYTES + 1);
                total += bytes.length;
                if (bytes.length > MAX_IMAGE_BYTES || total > 3L * MAX_IMAGE_BYTES) throw invalid("Gói khung giải nén ra quá lớn");
                String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                if (name.indexOf('/') != name.lastIndexOf('/')) throw invalid("Gói khung chỉ có một cấp thư mục");
                files.put(base, bytes);
            }
        } catch (IOException ex) {
            throw invalid("Tệp tải lên không phải .zip hợp lệ");
        }
        // Tệp không phải zip cũng đọc ra 0 mục chứ không báo lỗi.
        if (files.isEmpty()) throw invalid("Tệp tải lên không phải .zip hợp lệ hoặc gói trống");
        if (!files.containsKey("manifest.json")) throw invalid("Gói khung thiếu manifest.json");
        for (String name : files.keySet()) {
            if (!name.equals("manifest.json") && !name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,79}\\.(png|webp)")) {
                throw invalid("Gói khung chỉ được chứa manifest.json và ảnh .png/.webp (tệp lạ: " + name + ")");
            }
        }
        return files;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
