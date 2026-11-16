package com.kece.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kece.admin.dto.AdminUserVO;
import com.kece.admin.dto.UpdateRolesRequest;
import com.kece.admin.dto.UpdateStatusRequest;
import com.kece.admin.service.AdminService;
import com.kece.common.response.PageResult;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.UserAuthStateService;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserAuthStateService userAuthStateService = mock(UserAuthStateService.class);
    private final AdminService adminService =
            new AdminService(userMapper, passwordEncoder, userAuthStateService);

    @AfterEach
    void clearAuth() {
        AuthContextHolder.clear();
    }

    @Test
    void listUsersIncludesAvatarUrlInVo() {
        User withAvatar = new User();
        withAvatar.setId(1L);
        withAvatar.setUsername("admin");
        withAvatar.setEmail("admin@kece.edu");
        withAvatar.setAvatarUrl("/api/v1/uploads/avatars/avatar_1_123.png");
        withAvatar.setRoles("ADMIN");
        withAvatar.setStatus(1);
        withAvatar.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));

        User withoutAvatar = new User();
        withoutAvatar.setId(2L);
        withoutAvatar.setUsername("student1");
        withoutAvatar.setEmail("student1@kece.edu");
        withoutAvatar.setAvatarUrl(null);
        withoutAvatar.setRoles("USER");
        withoutAvatar.setStatus(1);
        withoutAvatar.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));

        when(userMapper.findPage(null, null, null, 0, 20))
                .thenReturn(List.of(withAvatar, withoutAvatar));
        when(userMapper.count(null, null, null)).thenReturn(2L);

        PageResult<AdminUserVO> page = adminService.listUsers(null, null, null, 1, 20);
        assertThat(page.getList()).hasSize(2);
        assertThat(page.getList().get(0).getAvatarUrl())
                .isEqualTo("/api/v1/uploads/avatars/avatar_1_123.png");
        assertThat(page.getList().get(1).getAvatarUrl()).isNull();
    }

    @Test
    void updateRolesAndStatusPreserveAvatarUrl() {
        AuthContextHolder.set(new AuthUser(1L, "admin", Set.of("ADMIN")));

        User targetBefore = new User();
        targetBefore.setId(2L);
        targetBefore.setUsername("teacher1");
        targetBefore.setEmail("teacher1@kece.edu");
        targetBefore.setAvatarUrl("/api/v1/uploads/avatars/avatar_2_999.png");
        targetBefore.setRoles("USER");
        targetBefore.setStatus(1);

        User targetAfterRole = new User();
        targetAfterRole.setId(2L);
        targetAfterRole.setUsername("teacher1");
        targetAfterRole.setEmail("teacher1@kece.edu");
        targetAfterRole.setAvatarUrl("/api/v1/uploads/avatars/avatar_2_999.png");
        targetAfterRole.setRoles("TEACHER");
        targetAfterRole.setStatus(1);

        User targetAfterDisable = new User();
        targetAfterDisable.setId(2L);
        targetAfterDisable.setUsername("teacher1");
        targetAfterDisable.setEmail("teacher1@kece.edu");
        targetAfterDisable.setAvatarUrl("/api/v1/uploads/avatars/avatar_2_999.png");
        targetAfterDisable.setRoles("TEACHER");
        targetAfterDisable.setStatus(0);

        when(userMapper.findById(2L))
                .thenReturn(targetBefore, targetAfterRole, targetAfterRole, targetAfterDisable);

        UpdateRolesRequest rolesReq = new UpdateRolesRequest();
        rolesReq.setRoles(List.of("TEACHER"));
        AdminUserVO roleUpdated = adminService.updateRoles(2L, rolesReq);
        assertThat(roleUpdated.getAvatarUrl())
                .isEqualTo("/api/v1/uploads/avatars/avatar_2_999.png");

        UpdateStatusRequest statusReq = new UpdateStatusRequest();
        statusReq.setStatus(0);
        AdminUserVO statusUpdated = adminService.updateStatus(2L, statusReq);
        assertThat(statusUpdated.getAvatarUrl())
                .isEqualTo("/api/v1/uploads/avatars/avatar_2_999.png");
    }
}
