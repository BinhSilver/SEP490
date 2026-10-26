package com.jlas.tutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.EmailService;
import com.jlas.auth.service.GoogleTokenService;
import com.jlas.auth.util.JwtUtil;
import com.jlas.config.AuthProperties;
import com.jlas.kaiwa.rag.RagPromptComposer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Chatbot gia sư mở cho mọi tài khoản đã đăng nhập, không cho khách. */
@SpringBootTest(properties = "spring.config.location=classpath:/application-ai-test.yaml")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TutorChatControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuthProperties authProperties;
    @Autowired PasswordEncoder passwords;
    @MockitoBean TutorChatService tutor;
    @MockitoBean EmailService email;
    @MockitoBean GoogleTokenService google;

    @Test
    void guestsCannotUseTheTutorButLearnersCan() throws Exception {
        when(tutor.status()).thenReturn(new TutorChatService.Status(true, 500));
        when(tutor.ask(anyLong(), eq("勉強"), any())).thenReturn(new TutorChatService.Reply("勉 = Miễn [D1]",
                List.of(new RagPromptComposer.Citation("D1", "KANJI", "勉", "Hán Việt: Miễn")), 15, 800));
        String body = "{\"question\":\"勉強\",\"history\":[{\"role\":\"user\",\"content\":\"こんにちは\"}]}";

        mvc.perform(get("/api/tutor/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tutor/chat").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        verify(tutor, never()).ask(anyLong(), any(), any());

        String learner = bearer(user("tutor-learner", "LEARNER"));
        mvc.perform(get("/api/tutor/status").header("Authorization", learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(post("/api/tutor/chat").contentType("application/json").content(body).header("Authorization", learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("勉 = Miễn [D1]"))
                .andExpect(jsonPath("$.data.sources[0].code").value("D1"))
                .andExpect(jsonPath("$.data.sources[0].title").value("勉"));
    }

    private String bearer(User user) {
        return "Bearer " + JwtUtil.generateToken(user.getEmail(), authProperties.jwtSecret(), authProperties.accessTokenTtl());
    }

    private User user(String name, String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> roles.save(Role.builder().name(roleName).build()));
        return users.findByEmail(name + "@example.test").orElseGet(() -> users.save(User.builder()
                .email(name + "@example.test").role(role).loginType("LOCAL")
                .passwordHash(passwords.encode("Dummy-test-password-123")).isVerified(true).build()));
    }
}
