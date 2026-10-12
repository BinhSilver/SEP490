package com.jlas.moderation.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Validation and rollback cleanup for local moderation uploads. */
public final class UploadFiles {
    private UploadFiles() {}
    public static byte[] read(MultipartFile file, long limit) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng chọn file");
        if (file.getSize() > limit) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                "File không được vượt quá " + limit / (1024 * 1024) + " MB");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > limit) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "File vượt quá dung lượng cho phép");
            return bytes;
        }
        catch (IOException e) { throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được file"); }
    }
    public static String extension(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    public static String displayName(String name) {
        String value = name == null ? "file" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return value.substring(0, Math.min(value.length(), 200));
    }
    public static void write(Path target, byte[] bytes) {
        try { Files.createDirectories(target.getParent()); Files.write(target, bytes); }
        catch (IOException e) { delete(target); throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không lưu được file"); }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) delete(target); }
            });
        }
    }
    public static void delete(Path target) {
        try { Files.deleteIfExists(target); } catch (IOException ignored) { /* best effort */ }
    }
}
