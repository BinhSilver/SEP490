package com.jlas.auth.service;

import com.jlas.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthUserDirectoryAdapter implements UserDirectoryPort {
    private final UserRepository userRepository;

    @Override
    public String displayName(Long userId) {
        return userRepository.findById(userId)
                .map(user -> {
                    String name = user.getFullName();
                    if (name != null && !name.isBlank()) return name.trim();
                    String email = user.getEmail();
                    return email != null && !email.isBlank() ? email.trim() : "Người dùng";
                })
                .orElse("Người dùng");
    }

    @Override
    public Map<Long, String> avatarUrls(Collection<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return result;
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return result;
        userRepository.findAllById(ids).forEach(user -> {
            String url = user.getAvatarUrl();
            if (url != null && !url.isBlank()) result.put(user.getId(), url.trim());
        });
        return result;
    }
}
