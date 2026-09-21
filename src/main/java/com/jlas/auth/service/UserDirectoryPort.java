package com.jlas.auth.service;

/** Read-only user information exposed to other feature modules. */
public interface UserDirectoryPort {
    String displayName(Long userId);
}
