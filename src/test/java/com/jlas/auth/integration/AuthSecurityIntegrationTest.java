package com.jlas.auth.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("AUTH-TC-055: API profile từ chối request chưa xác thực")
    void protectedProfileApiRejectsAnonymousRequest() throws Exception {
        mvc.perform(get("/api/auth/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("AUTH-TC-054: trang profile public theo cấu hình hiện tại")
    void profilePageIsPublicInSecurityConfiguration() throws Exception {
        mvc.perform(get("/profile"))
                .andExpect(status().isOk())       // kiểm tra HTTP 200 trước
                .andExpect(view().name("profile")); // rồi mới kiểm tra template
    }

    @Test
    @DisplayName("AUTH-TC-057: token giả mạo không cấp quyền truy cập")
    void tamperedBearerTokenIsTreatedAsAnonymous() throws Exception {
        mvc.perform(get("/api/auth/profile").header("Authorization", "Bearer not.a.valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AUTH-TC-001: endpoint register vẫn public")
    void publicRegisterEndpointReachesValidationWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
