package com.jlas.game.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.enums.GameType;
import com.jlas.game.repository.GameSkinRepository;
import com.jlas.game.util.GameShopCatalog;
import com.jlas.game.util.GameShopCatalog.ShopItem;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GameSkinCatalogService {
    private final GameSkinRepository repository;

    public record SkinInput(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{1,39}") String code,
                            @NotNull GameType gameType, @NotBlank @Size(max=100) String name,
                            @Size(max=500) String description, @Min(0) @Max(1000000) int price,
                            @NotBlank @Size(max=1024) String image,
                            @NotBlank @Size(max=40) String renderCode, boolean active) { }

    public List<ShopItem> all() { return repository.all(); }
    public Optional<ShopItem> find(String code) { return repository.find(code); }
    public ShopItem require(String code) {
        return find(code).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy trang phục"));
    }
    public ShopItem starter(GameType game) {
        return all().stream().filter(item -> item.gameType() == game && item.starter()).findFirst().orElse(null);
    }

    private ShopItem validated(SkinInput input, ShopItem current) {
        if (!GameShopCatalog.SHOP_GAMES.contains(input.gameType())) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Game chưa hỗ trợ trang phục");
        if (current != null && (!current.code().equals(input.code()) || current.gameType() != input.gameType()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không đổi mã hoặc game của trang phục đã tạo");
        if (current != null && current.deleted()) throw new ApiException(ErrorCode.CONFLICT, "Trang phục đã bị xóa");
        String image = input.image().trim();
        if (!(image.startsWith("/arena/") || image.startsWith("/uploads/game-skins/")) || !image.matches("(?i).+\\.(png|jpe?g|webp)") || image.contains("..") || image.contains("\\") || image.contains("?") || image.contains("#"))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn ảnh trò chơi hoặc tải ảnh trang phục lên");
        boolean custom = "CUSTOM".equals(input.renderCode());
        if (!custom && GameShopCatalog.find(input.renderCode()).filter(item -> item.gameType() == input.gameType()).isEmpty())
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mẫu chuyển động không hợp lệ");
        boolean starter = current != null && current.starter();
        if (starter && (input.price() != 0 || !input.active())) throw new ApiException(ErrorCode.CONFLICT, "Trang phục mặc định phải miễn phí và luôn hoạt động");
        return new ShopItem(current == null ? 0 : current.id(), input.code(), input.gameType(), input.name().trim(),
                input.description() == null ? "" : input.description().trim(), input.price(), image, starter, input.renderCode(), input.active(), false);
    }
    @Transactional
    public ShopItem create(SkinInput input) {
        var item = validated(input, null);
        try { repository.create(item); }
        catch (DuplicateKeyException ex) { throw new ApiException(ErrorCode.CONFLICT, "Mã trang phục đã tồn tại"); }
        return require(item.code());
    }
    @Transactional
    public ShopItem update(String code, SkinInput input) {
        var item = validated(input, require(code));
        repository.update(item);
        return require(code);
    }
    @Transactional
    public void delete(String code) {
        if (require(code).starter()) throw new ApiException(ErrorCode.CONFLICT, "Không xóa trang phục mặc định");
        repository.delete(code);
    }
}
