package com.jlas.auth.service;

import com.jlas.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
