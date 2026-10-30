package com.jlas.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.enums.GameType;
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
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Gói skin game (.zip) do admin tải lên: {@code manifest.json} cùng định dạng với skin gốc của game, kèm các ảnh PNG.
 * Đường dẫn ảnh trong manifest là đường dẫn tương đối trong gói. Gói hợp lệ được lưu ở
 * {@code uploads/game-skins/packs/<uuid>/} (mỗi lần tải lên một thư mục mới, nên người đang chơi không bị đổi giữa trận)
 * kèm {@code preview.png} cắt từ khung đầu tiên để làm ảnh đại diện trong cửa hàng.
 *
 * <p>Bắt cá: {@code cellSize}, {@code baseline}, {@code portrait?}, {@code lobby?} và đủ 6 hành động
 * idle, run, catch_success, catch_wrong, win, lose. Bắn máy bay: {@code name}, {@code color}, {@code body} và
 * {@code effects} (mỗi cái 16 khung, 4 hàng × 4 khung), cùng 4 hành động idle, boost, shoot, hit.
 * Xem docs/game-skin-packs.md.
 */
@Service
public class GameSkinPackService {
    public static final String PACK_URL_PREFIX = "/uploads/game-skins/packs/";
    /** Bộ vũ khí có sẵn (viết bằng code trong game Bắn máy bay) mà máy bay dùng gói skin có thể mượn. */
    public static final List<String> WEAPON_THEMES = List.of("halo-fighter", "aegis-frame", "split-core", "void-blade");

    static final long MAX_ZIP_BYTES = 25L * 1024 * 1024;
    static final long MAX_UNZIPPED_BYTES = 80L * 1024 * 1024;
    static final int MAX_ENTRIES = 64;
    static final int MAX_IMAGE_SIDE = 4096;
    static final List<String> CAT_ACTIONS = List.of("idle", "run", "catch_success", "catch_wrong", "win", "lose");
    static final List<String> SHIP_ACTIONS = List.of("idle", "boost", "shoot", "hit");
    static final int SHIP_FRAMES = 16;

    private static final String PACK_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String FILE_NAME = "[A-Za-z0-9_-][A-Za-z0-9_.-]{0,79}";
    /** Đường dẫn tương đối trong gói: tối đa 3 cấp thư mục, chỉ ký tự an toàn, phải là PNG. */
    private static final String RELATIVE_PNG = "(" + FILE_NAME + "/){0,3}" + FILE_NAME + "\\.png";
    private static final String GAME_MARKER = "game.txt";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Skin gốc có thể tải làm gói mẫu: mã trong cửa hàng → thư mục tài nguyên trong game. */
    private static final Map<String, String> CAT_TEMPLATES = Map.of("cat-cowboy", "cowboy", "cat-wizard", "wizard",
            "cat-godzilla", "godzilla", "cat-ronova", "ronova", "cat-zenitsu", "zenitsu", "cat-tanjiro", "tanjiro");

    private final Path root;

    public GameSkinPackService(@Value("${game.skin-packs.dir:uploads/game-skins/packs}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Kết quả tải gói: đường dẫn gói, ảnh đại diện và mô tả ngắn để admin kiểm tra. */
    public record PackInfo(String packPath, String preview, String summary) {
    }

    // ── Tải lên ──────────────────────────────────────────────────────────────

    public PackInfo upload(GameType game, byte[] zip) {
        if (game != GameType.NEKO_CATCH && game != GameType.AIR_DEFENSE) {
            throw invalid("Game này chưa hỗ trợ gói skin");
        }
        if (zip == null || zip.length == 0) throw invalid("Chọn tệp .zip của gói skin");
        if (zip.length > MAX_ZIP_BYTES) throw invalid("Gói skin tối đa 25 MB");
        Map<String, byte[]> files = unzip(zip);
        byte[] manifestBytes = files.get("manifest.json");
        if (manifestBytes == null) throw invalid("Gói thiếu manifest.json ở thư mục gốc");
        JsonNode manifest;
        try {
            manifest = JSON.readTree(manifestBytes);
        } catch (IOException ex) {
            throw invalid("manifest.json không phải JSON hợp lệ");
        }
        if (!(manifest instanceof ObjectNode)) throw invalid("manifest.json phải là một đối tượng JSON");

        Images images = new Images(files);
        String summary;
        BufferedImage preview;
        if (game == GameType.NEKO_CATCH) {
            summary = validateCat(manifest, images);
            preview = catPreview(manifest, images);
        } else {
            summary = validateShip(manifest, images);
            preview = shipPreview(manifest, images);
        }
        String id = UUID.randomUUID().toString();
        Path directory = root.resolve(id);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("manifest.json"), manifestBytes);
            for (String path : images.used()) {
                Path target = directory.resolve(path).normalize();
                if (!target.startsWith(directory)) throw invalid("Đường dẫn ảnh không hợp lệ: " + path);
                Files.createDirectories(target.getParent());
                Files.write(target, files.get(path));
            }
            ImageIO.write(preview, "png", directory.resolve("preview.png").toFile());
            Files.writeString(directory.resolve(GAME_MARKER), game.name());
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không lưu được gói skin. Hãy thử lại.");
        }
        String packPath = PACK_URL_PREFIX + id + "/";
        return new PackInfo(packPath, packPath + "preview.png", summary);
    }

    /** Gói đã tải lên, đúng game; dùng khi lưu trang phục để không trỏ tới gói lạ hoặc của game khác. */
    public void requirePack(String packPath, GameType game) {
        if (packPath == null || !packPath.matches(java.util.regex.Pattern.quote(PACK_URL_PREFIX) + PACK_ID + "/")) {
            throw invalid("Tải gói skin (.zip) lên trước khi lưu");
        }
        Path directory = root.resolve(packPath.substring(PACK_URL_PREFIX.length(), packPath.length() - 1));
        try {
            if (!Files.isRegularFile(directory.resolve("manifest.json"))
                    || !game.name().equals(Files.readString(directory.resolve(GAME_MARKER)).trim())) {
                throw invalid("Gói skin không tồn tại hoặc thuộc game khác");
            }
        } catch (IOException ex) {
            throw invalid("Gói skin không tồn tại hoặc thuộc game khác");
        }
    }

    // ── Kiểm tra manifest ────────────────────────────────────────────────────

    private static String validateCat(JsonNode manifest, Images images) {
        int cell = positiveInt(manifest, "cellSize", MAX_IMAGE_SIDE);
        int baseline = positiveInt(manifest, "baseline", MAX_IMAGE_SIDE);
        if (baseline > cell) throw invalid("baseline phải nằm trong ô (≤ cellSize)");
        if (manifest.hasNonNull("portrait")) images.require(text(manifest, "portrait"));
        JsonNode actions = manifest.path("actions");
        int frames = 0;
        for (String action : CAT_ACTIONS) {
            if (!actions.has(action)) throw invalid("Thiếu hành động \"" + action + "\" trong actions");
            frames += validateCatAction(action, actions.get(action), cell, images);
        }
        if (manifest.hasNonNull("lobby")) validateCatAction("lobby", manifest.get("lobby"), cell, images);
        return "Nhân vật Bắt cá: 6 hành động, " + frames + " khung hình";
    }

    private static int validateCatAction(String name, JsonNode config, int cell, Images images) {
        int count = rangeInt(config, "frameCount", 1, 64, name);
        int columns = rangeInt(config, "columns", 1, 64, name);
        rangeInt(config, "frameDurationMs", 16, 5000, name);
        if (config.hasNonNull("loop") && !config.get("loop").isBoolean()) throw invalid(name + ".loop phải là true/false");
        BufferedImage sheet = images.require(text(config, "path"));
        JsonNode frames = config.path("sourceFrames");
        if (frames.isArray() && !frames.isEmpty()) {
            if (frames.size() < count) throw invalid(name + ".sourceFrames cần ít nhất " + count + " khung");
            for (int i = 0; i < count; i++) {
                JsonNode frame = frames.get(i);
                rect(frame, sheet, name + ".sourceFrames[" + i + "]");
                number(frame, "anchorX", name);
                number(frame, "anchorY", name);
            }
        } else {
            int rows = (count + columns - 1) / columns;
            if (columns * cell > sheet.getWidth() || rows * cell > sheet.getHeight()) {
                throw invalid(name + ": ảnh " + sheet.getWidth() + "×" + sheet.getHeight() + " không đủ chỗ cho "
                        + count + " khung " + cell + "px (" + columns + " cột)");
            }
        }
        return count;
    }

    private static String validateShip(JsonNode manifest, Images images) {
        if (!text(manifest, "color").matches("#[0-9a-fA-F]{6}")) throw invalid("color phải có dạng #rrggbb");
        BufferedImage body = shipSheet(manifest, "body", images);
        shipSheet(manifest, "effects", images);
        JsonNode actions = manifest.path("actions");
        for (String action : SHIP_ACTIONS) {
            JsonNode config = actions.path(action);
            if (config.isMissingNode()) throw invalid("Thiếu hành động \"" + action + "\" trong actions");
            int count = rangeInt(config, "frameCount", 1, 4, action);
            rangeInt(config, "frameDurationMs", 16, 5000, action);
            JsonNode frames = config.path("sourceFrames");
            if (!frames.isArray() || frames.size() != count) {
                throw invalid(action + ".sourceFrames cần đúng " + count + " khung");
            }
            for (int i = 0; i < count; i++) {
                JsonNode frame = frames.get(i);
                rect(frame, body, action + ".sourceFrames[" + i + "]");
                number(frame, "centerX", action);
                number(frame, "centerY", action);
            }
        }
        return "Máy bay: thân và lửa động cơ mỗi loại " + SHIP_FRAMES + " khung, 4 hành động";
    }

    /** body / effects: 16 khung (4 hàng × 4 khung, hàng theo thứ tự idle, boost, shoot, hit). */
    private static BufferedImage shipSheet(JsonNode manifest, String key, Images images) {
        JsonNode sheet = manifest.path(key);
        BufferedImage image = images.require(text(sheet, "path"));
        JsonNode frames = sheet.path("frames");
        if (!frames.isArray() || frames.size() != SHIP_FRAMES) {
            throw invalid(key + ".frames cần đúng " + SHIP_FRAMES + " khung (4 hàng × 4 khung)");
        }
        for (int i = 0; i < SHIP_FRAMES; i++) {
            JsonNode frame = frames.get(i);
            rect(frame, image, key + ".frames[" + i + "]");
            number(frame, "centerX", key);
            number(frame, "centerY", key);
        }
        return image;
    }

    // ── Ảnh đại diện ─────────────────────────────────────────────────────────

    private static BufferedImage catPreview(JsonNode manifest, Images images) {
        if (manifest.hasNonNull("portrait")) return images.require(text(manifest, "portrait"));
        JsonNode idle = manifest.path("actions").path("idle");
        BufferedImage sheet = images.require(text(idle, "path"));
        JsonNode frame = idle.path("sourceFrames").path(0);
        if (frame.isObject()) return crop(sheet, frame);
        int cell = manifest.path("cellSize").asInt();
        return sheet.getSubimage(0, 0, cell, cell);
    }

    private static BufferedImage shipPreview(JsonNode manifest, Images images) {
        BufferedImage body = images.require(text(manifest.path("body"), "path"));
        return crop(body, manifest.path("actions").path("idle").path("sourceFrames").path(0));
    }

    private static BufferedImage crop(BufferedImage image, JsonNode frame) {
        return image.getSubimage(frame.path("x").asInt(), frame.path("y").asInt(),
                frame.path("width").asInt(), frame.path("height").asInt());
    }

    // ── Gói mẫu từ skin gốc ──────────────────────────────────────────────────

    /** Mã skin gốc có gói mẫu, theo game. */
    public List<String> templates(GameType game) {
        if (game == GameType.NEKO_CATCH) return CAT_TEMPLATES.keySet().stream().sorted().toList();
        return WEAPON_THEMES.stream().map(name -> "plane-" + name).toList();
    }

    /**
     * Zip của một skin gốc theo đúng định dạng gói (đường dẫn ảnh đã đổi sang tương đối), để hoạ sĩ dùng làm khuôn.
     * Tải gói mẫu lên lại phải hợp lệ (có test).
     */
    public byte[] template(String code) {
        String base, folder;
        if (CAT_TEMPLATES.containsKey(code)) {
            base = "static/arena/app/neko";
            folder = "/sprites/actions/" + CAT_TEMPLATES.get(code) + "/";
        } else if (code != null && code.startsWith("plane-") && WEAPON_THEMES.contains(code.substring(6))) {
            base = "static/arena/app/air-defense/";
            folder = "assets/space/skins/" + code.substring(6) + "/";
        } else {
            throw new ApiException(ErrorCode.NOT_FOUND, "Skin này không có gói mẫu");
        }
        try {
            ObjectNode manifest = (ObjectNode) JSON.readTree(read(base + folder + "manifest.json"));
            Map<String, byte[]> entries = new LinkedHashMap<>();
            relativize(manifest, base, folder, entries);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.closeEntry();
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không tạo được gói mẫu");
        }
    }

    /** Đổi mọi trường "path" sang đường dẫn tương đối trong gói và gom file ảnh tương ứng. */
    private static void relativize(JsonNode node, String base, String folder, Map<String, byte[]> entries) throws IOException {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if (value.isTextual() && ("path".equals(key) || "portrait".equals(key))) {
                    // Manifest gốc có thể kèm tham số chống cache (…/godzilla.png?v=…): gói dùng tên tệp thật.
                    String source = value.asText().replaceFirst("[?#].*$", "");
                    // Ngoài thư mục skin (chân dung, sảnh chờ): giữ thư mục cha để portraits/x.png và lobby/x.png không đè nhau.
                    String[] parts = source.split("/");
                    String relative = source.startsWith(folder) ? source.substring(folder.length())
                            : parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : parts[parts.length - 1];
                    if (!entries.containsKey(relative)) entries.put(relative, read(base + source));
                    object.put(key, relative);
                } else {
                    relativize(value, base, folder, entries);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) relativize(child, base, folder, entries);
        }
    }

    private static byte[] read(String classpath) throws IOException {
        try (InputStream in = new ClassPathResource(classpath.replace("//", "/")).getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── Giải nén an toàn ─────────────────────────────────────────────────────

    /** Tên file → nội dung; chặn đường dẫn độc hại, file lạ và zip bomb. Bỏ thư mục bọc ngoài nếu có. */
    static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw invalid("Gói có quá nhiều tệp (tối đa " + MAX_ENTRIES + ")");
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store")
                        || name.equals(".DS_Store") || name.endsWith("Thumbs.db")) {
                    continue;
                }
                if (name.contains("\\") || name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw invalid("Tên tệp không hợp lệ trong gói: " + name);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_UNZIPPED_BYTES) throw invalid("Gói giải nén ra quá lớn (tối đa 80 MB)");
                    out.write(buffer, 0, read);
                }
                files.put(name, out.toByteArray());
            }
        } catch (IOException ex) {
            throw invalid("Tệp tải lên không phải .zip hợp lệ");
        }
        // Tệp không phải zip cũng đọc ra 0 mục chứ không báo lỗi.
        if (files.isEmpty()) throw invalid("Tệp tải lên không phải .zip hợp lệ hoặc gói trống");
        // Nén cả thư mục "skin-cua-toi/" thì mọi tệp nằm trong một thư mục bọc ngoài: bỏ phần đó đi.
        if (!files.containsKey("manifest.json")) {
            String wrapper = files.keySet().stream().filter(name -> name.endsWith("/manifest.json")
                    && name.indexOf('/') == name.length() - "/manifest.json".length()).findFirst()
                    .map(name -> name.substring(0, name.length() - "manifest.json".length())).orElse(null);
            if (wrapper != null && files.keySet().stream().allMatch(name -> name.startsWith(wrapper))) {
                Map<String, byte[]> stripped = new LinkedHashMap<>();
                files.forEach((name, bytes) -> stripped.put(name.substring(wrapper.length()), bytes));
                files = stripped;
            }
        }
        for (String name : files.keySet()) {
            if (!name.equals("manifest.json") && !name.matches(RELATIVE_PNG)) {
                throw invalid("Gói chỉ được chứa manifest.json và ảnh .png (tệp lạ: " + name + ")");
            }
        }
        return files;
    }

    /** Ảnh PNG trong gói, đọc một lần; ghi nhớ ảnh nào manifest dùng để chỉ lưu những ảnh đó. */
    private static final class Images {
        private final Map<String, byte[]> files;
        private final Map<String, BufferedImage> decoded = new LinkedHashMap<>();

        Images(Map<String, byte[]> files) {
            this.files = files;
        }

        BufferedImage require(String path) {
            if (!path.matches(RELATIVE_PNG)) {
                throw invalid("Đường dẫn ảnh phải là tệp .png tương đối trong gói (ví dụ idle/sheet.png): " + path);
            }
            BufferedImage cached = decoded.get(path);
            if (cached != null) return cached;
            byte[] bytes = files.get(path);
            if (bytes == null) throw invalid("Không tìm thấy " + path + " trong gói");
            BufferedImage image;
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid(path + " không phải ảnh PNG");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    if (!"png".equalsIgnoreCase(reader.getFormatName())) throw invalid(path + " không phải ảnh PNG");
                    if (reader.getWidth(0) > MAX_IMAGE_SIDE || reader.getHeight(0) > MAX_IMAGE_SIDE) {
                        throw invalid(path + " vượt quá " + MAX_IMAGE_SIDE + " × " + MAX_IMAGE_SIDE);
                    }
                    image = reader.read(0);
                } finally {
                    reader.dispose();
                }
            } catch (IOException ex) {
                throw invalid(path + " không đọc được");
            }
            decoded.put(path, image);
            return image;
        }

        Set<String> used() {
            return decoded.keySet();
        }
    }

    // ── Đọc trường JSON ──────────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid("Thiếu trường \"" + field + "\"");
        return value.asText();
    }

    private static int positiveInt(JsonNode node, String field, int max) {
        return rangeInt(node, field, 1, max, "manifest");
    }

    private static int rangeInt(JsonNode node, String field, int min, int max, String where) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt() || !value.isIntegralNumber() || value.asInt() < min || value.asInt() > max) {
            throw invalid(where + "." + field + " phải là số nguyên từ " + min + " đến " + max);
        }
        return value.asInt();
    }

    private static void number(JsonNode node, String field, String where) {
        if (!node.path(field).isNumber()) throw invalid(where + ": mỗi khung cần " + field + " là số");
    }

    /** Khung {x, y, width, height} nằm trọn trong ảnh. */
    private static void rect(JsonNode frame, BufferedImage image, String where) {
        if (frame == null || !frame.isObject()) throw invalid(where + " phải là {x, y, width, height}");
        int x = frame.path("x").asInt(-1), y = frame.path("y").asInt(-1);
        int width = frame.path("width").asInt(0), height = frame.path("height").asInt(0);
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > image.getWidth() || y + height > image.getHeight()) {
            throw invalid(where + " nằm ngoài ảnh " + image.getWidth() + "×" + image.getHeight());
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
