package com.kece.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.GlobalExceptionHandler;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.UserAuthStateService;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.exam.service.ExamRankService;
import com.kece.user.controller.ProfileController;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import com.kece.user.service.ProfileService;
import com.kece.user.service.UserDailyAcService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProfileControllerTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
        ProfileService profileService =
                new ProfileService(
                        userMapper,
                        mock(ExamMapper.class),
                        mock(ExamSubmissionMapper.class),
                        mock(ExamRankService.class),
                        passwordEncoder,
                        mock(UserDailyAcService.class),
                        mock(UserAuthStateService.class));
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ProfileController(profileService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        objectMapper = new ObjectMapper();

        AuthContextHolder.set(new AuthUser(27L, "zheco68", Set.of("USER")));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void updateProfile_rejectsUnsafeGithubProtocol() throws Exception {
        User current = sampleUser(27L, "zheco68", "zheco@kece.edu");
        when(userMapper.findById(27L)).thenReturn(current);

        mockMvc.perform(
                        put("/users/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "bio",
                                                        "hello",
                                                        "githubUrl",
                                                        "javascript:alert(1)"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        verify(userMapper, never()).updateProfile(any(), any(), any());
    }

    @Test
    void updateProfile_acceptsHttpsGithubUrl() throws Exception {
        User updated = sampleUser(27L, "zheco68", "zheco@kece.edu");
        updated.setBio("new bio");
        updated.setGithubUrl("https://github.com/zheco68");
        when(userMapper.findById(27L)).thenReturn(updated);

        mockMvc.perform(
                        put("/users/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "bio",
                                                        "new bio",
                                                        "githubUrl",
                                                        "https://github.com/zheco68"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bio").value("new bio"))
                .andExpect(jsonPath("$.data.githubUrl").value("https://github.com/zheco68"));

        verify(userMapper).updateProfile(eq(27L), eq("new bio"), eq("https://github.com/zheco68"));
    }

    @Test
    void updateProfile_rejectsInvalidOrDuplicateUsernameAndAcceptsValidRenameWithoutTokenBump()
            throws Exception {
        User current = sampleUser(27L, "zheco68", "zheco@kece.edu");
        when(userMapper.findById(27L)).thenReturn(current);

        // Reject '@' in username
        mockMvc.perform(
                        put("/users/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("username", "bad@user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // Reject duplicate username owned by another user
        User other = sampleUser(99L, "taken_name", "other@kece.edu");
        when(userMapper.findByUsername("taken_name")).thenReturn(other);
        mockMvc.perform(
                        put("/users/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("username", "taken_name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // Accept valid Chinese/alphanumeric/underscore/hyphen rename
        User renamed = sampleUser(27L, "新昵称_01-a", "zheco@kece.edu");
        renamed.setBio("bio");
        when(userMapper.findByUsername("新昵称_01-a")).thenReturn(null);
        when(userMapper.findById(27L)).thenReturn(current, renamed);

        mockMvc.perform(
                        put("/users/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("username", "新昵称_01-a", "bio", "bio"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("新昵称_01-a"));

        verify(userMapper, never()).incrementTokenVersion(27L);
    }

    @Test
    void changeEmail_validatesPasswordUniquenessAndBumpsTokenVersion() throws Exception {
        User current = sampleUser(27L, "zheco68", "zheco@kece.edu");
        when(userMapper.findById(27L)).thenReturn(current);

        // Wrong current password
        mockMvc.perform(
                        put("/users/email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        "wrongpass",
                                                        "newEmail",
                                                        "next@kece.edu"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // Duplicate email owned by another user
        User other = sampleUser(99L, "other", "taken@kece.edu");
        when(userMapper.findByEmail("taken@kece.edu")).thenReturn(other);
        mockMvc.perform(
                        put("/users/email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        "secret123",
                                                        "newEmail",
                                                        "Taken@kece.edu"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // Valid email rebind lowercases email and bumps token version
        when(userMapper.findByEmail("next@kece.edu")).thenReturn(null);
        mockMvc.perform(
                        put("/users/email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        "secret123",
                                                        "newEmail",
                                                        "Next@KECE.edu"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userMapper).updateEmail(27L, "next@kece.edu");
    }

    private User sampleUser(Long id, String username, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode("secret123"));
        u.setRoles("USER");
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        return u;
    }
}
