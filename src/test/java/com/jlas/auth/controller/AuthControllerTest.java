package com.jlas.auth.controller;

import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.service.AccountSecurityService;
import com.jlas.auth.service.AuthService;
import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.GlobalApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock AuthService authService;
    @Mock AvatarUploadService avatarUploadService;
    @Mock AccountSecurityService accountSecurityService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, avatarUploadService, accountSecurityService))
                .setControllerAdvice(new GlobalApiExceptionHandler()).build();
    }

    @Test
    @DisplayName("AUTH-TC-001: register hợp lệ trả HTTP 201")
    void registerReturnsCreated() throws Exception {
        when(authService.register(any())).thenReturn(UserResponse.builder().id(1L).email("new@example.com").build());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"Valid@123\",\"confirmPassword\":\"Valid@123\",\"fullName\":\"Nguyen Van An\",\"japaneseLevel\":\"N5\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    @Test
    @DisplayName("AUTH-TC-009: register thiếu email trả validation error")
    void registerWithoutEmailReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Valid@123\",\"confirmPassword\":\"Valid@123\",\"fullName\":\"Nguyen Van An\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @DisplayName("AUTH-TC-039/AUTH-TC-053: login trả token và HttpOnly access cookie")
    void loginReturnsTokensAndHttpOnlyCookie() throws Exception {
        LoginResponse response = LoginResponse.builder().accessToken("access-value").refreshToken("refresh-value")
                .tokenType("Bearer").expiresIn(900).user(UserResponse.builder().id(2L).build()).build();
        when(authService.login(any(), any(), any())).thenReturn(response);
        mvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("127.0.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"learner@example.com\",\"password\":\"Valid@123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("access-value"))
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("access_token=access-value"),
                                org.hamcrest.Matchers.containsString("HttpOnly")))));
    }

    @Test
    @DisplayName("AUTH-TC-043: login email sai định dạng trả HTTP 400")
    void loginRejectsMalformedEmail() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad-email\",\"password\":\"Valid@123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @DisplayName("AUTH-TC-069: forgot-password không tiết lộ email tồn tại")
    void forgotPasswordUsesGenericSuccessResponse() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

}
