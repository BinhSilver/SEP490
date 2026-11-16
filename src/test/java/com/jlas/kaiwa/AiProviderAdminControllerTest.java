package com.jlas.kaiwa;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jlas.auth.entity.Role;
import com.jlas.dictionary.kanjimap.KanjiMapCatalog;
import com.jlas.dictionary.kanjimap.KanjiMapContentService;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.EmailService;
import com.jlas.auth.service.GoogleTokenService;
import com.jlas.auth.util.JwtUtil;
import com.jlas.config.AuthProperties;
import com.jlas.kaiwa.rag.RagIndexService;
import com.jlas.kaiwa.rag.RagRetriever;
import com.jlas.kaiwa.rag.RagTutorService;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Endpoint chẩn đoán model AI và quản trị RAG chỉ dành cho ADMIN; người học và khách không gọi được. */
@SpringBootTest(properties = "spring.config.location=classpath:/application-ai-test.yaml")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AiProviderAdminControllerTest {
    private static final String URL = "/api/admin/system/ai/health";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuthProperties authProperties;
    @Autowired PasswordEncoder passwords;
    @MockitoBean OpenRouterClient llm;
    @MockitoBean EmailService email;
    @MockitoBean GoogleTokenService google;
    @MockitoBean RagIndexService ragIndex;
    @MockitoBean RagRetriever ragRetriever;
    @MockitoBean RagTutorService ragTutor;
    @MockitoBean KanjiMapCatalog kanjiMapCatalog;
    @MockitoBean KanjiMapContentService kanjiMapContentService;

    @Test
    void onlyAdminCanProbeTheModel() throws Exception {
        when(llm.probe()).thenReturn(new OpenRouterClient.ProbeResult("ollama", "qwen3.5:4b", true, 420, null, 3));

        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("Authorization", bearer(user("ai-learner", "LEARNER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL).header("Authorization", bearer(user("ai-moderator", "MODERATOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL).header("Authorization", bearer(user("ai-admin", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("ollama"))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.latencyMs").value(420));
    }

    @Test
    void onlyAdminCanRebuildOrQueryTheRagIndex() throws Exception {
        String reindex = "/api/admin/system/ai/rag/reindex";
        when(ragIndex.reindex(any())).thenReturn(new RagIndexService.Report("jlas_content_bge_m3", "bge-m3", null, 234, 0, 234, 0, 12));

        mvc.perform(post(reindex)).andExpect(status().isUnauthorized());
        mvc.perform(post(reindex).header("Authorization", bearer(user("rag-learner", "LEARNER")))).andExpect(status().isForbidden());
        mvc.perform(post(reindex).header("Authorization", bearer(user("rag-moderator", "MODERATOR")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/system/ai/rag/search").param("q", "勉強")
                .header("Authorization", bearer(user("rag-learner", "LEARNER")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/system/ai/rag/ask").contentType("application/json").content("{\"question\":\"勉強\"}")
                .header("Authorization", bearer(user("rag-learner", "LEARNER")))).andExpect(status().isForbidden());
        verify(ragIndex, never()).reindex(any());
        verify(ragTutor, never()).ask(any());

        mvc.perform(post(reindex).header("Authorization", bearer(user("rag-admin", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks").value(234))
                .andExpect(jsonPath("$.data.embedded").value(0));
        mvc.perform(post("/api/admin/system/ai/rag/ask").contentType("application/json").content("{\"question\":\" \"}")
                .header("Authorization", bearer(user("rag-admin", "ADMIN")))).andExpect(status().isBadRequest());
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
