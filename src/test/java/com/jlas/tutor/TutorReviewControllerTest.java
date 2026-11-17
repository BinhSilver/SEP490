package com.jlas.tutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.jlas.dictionary.kanjimap.KanjiMapCatalog;
import com.jlas.dictionary.kanjimap.KanjiMapContentService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Hàng chờ duyệt câu trả lời AI chỉ dành cho CONTENT_CREATOR và ADMIN; người học và moderator không vào được. */
@SpringBootTest(properties = "spring.config.location=classpath:/application-ai-test.yaml")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TutorReviewControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuthProperties authProperties;
    @Autowired PasswordEncoder passwords;
    @MockitoBean TutorReviewQueue queue;
    @MockitoBean EmailService email;
    @MockitoBean GoogleTokenService google;
    @MockitoBean KanjiMapCatalog kanjiMapCatalog;
    @MockitoBean KanjiMapContentService kanjiMapContentService;

    @Test
    void onlyContentCreatorsAndAdminsReviewAndInputIsChecked() throws Exception {
        when(queue.list(anyString(), anyString(), any(), anyInt(), anyInt())).thenReturn(new TutorReviewQueue.Page(List.of(), 0, 20, 0));
        when(queue.updateAnswer(anyLong(), anyString(), any())).thenReturn(true);
        when(queue.dictionaryEntryId("つまらない")).thenReturn(42L);
        when(queue.dictionaryEntryId("ほげほげ")).thenReturn(null);
        when(queue.updateAntonym(anyLong(), anyString(), any())).thenReturn(true);

        mvc.perform(get("/api/admin/tutor-review/items")).andExpect(status().isUnauthorized());
        for (String role : List.of("LEARNER", "MODERATOR")) {
            mvc.perform(get("/api/admin/tutor-review/items").header("Authorization", bearer(user("review-" + role, role))))
                    .andExpect(status().isForbidden());
        }
        verify(queue, never()).list(anyString(), anyString(), any(), anyInt(), anyInt());

        String creator = bearer(user("review-creator", "CONTENT_CREATOR"));
        mvc.perform(get("/api/admin/tutor-review/items?kind=antonym&status=pending").header("Authorization", creator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(queue).list("ANTONYM", "PENDING", null, 0, 20);
        mvc.perform(get("/api/admin/tutor-review/items").header("Authorization", bearer(user("review-admin", "ADMIN"))))
                .andExpect(status().isOk());

        mvc.perform(put("/api/admin/tutor-review/answers/1").header("Authorization", creator).contentType("application/json")
                .content("{\"status\":\"APPROVED\",\"answer\":\"  đã sửa  \"}")).andExpect(status().isOk());
        verify(queue).updateAnswer(1L, "APPROVED", "đã sửa");
        mvc.perform(put("/api/admin/tutor-review/answers/1").header("Authorization", creator).contentType("application/json")
                .content("{\"status\":\"DONE\"}")).andExpect(status().isBadRequest());

        mvc.perform(put("/api/admin/tutor-review/antonyms/3").header("Authorization", creator).contentType("application/json")
                .content("{\"status\":\"APPROVED\",\"antonym\":\"つまらない\"}")).andExpect(status().isOk());
        verify(queue).updateAntonym(3L, "APPROVED", 42L);
        mvc.perform(put("/api/admin/tutor-review/antonyms/3").header("Authorization", creator).contentType("application/json")
                .content("{\"status\":\"APPROVED\",\"antonym\":\"ほげほげ\"}")).andExpect(status().isBadRequest());
        verify(queue, never()).updateAntonym(eq(3L), eq("APPROVED"), isNull());
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
