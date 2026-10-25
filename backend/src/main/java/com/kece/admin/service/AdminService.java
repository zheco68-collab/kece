package com.kece.admin.service;

import com.kece.admin.dto.AdminUserVO;
import com.kece.admin.dto.ResetPasswordRequest;
import com.kece.admin.dto.UpdateRolesRequest;
import com.kece.admin.dto.UpdateStatusRequest;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台用户管理业务逻辑。 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Set<String> VALID_ROLES = Set.of("USER", "TEACHER", "ADMIN");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<AdminUserVO> listUsers(
            String keyword, String role, Integer status, int page, int size) {
        if (role != null && !role.isBlank() && !VALID_ROLES.contains(role)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色参数不合法");
        }
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态参数不合法");
        }
        PageParam p = PageParam.of(page, size);
        List<AdminUserVO> list =
                userMapper.findPage(keyword, role, status, p.offset(), p.size()).stream()
                        .map(this::toVO)
                        .toList();
        long total = userMapper.count(keyword, role, status);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    @Transactional
    public AdminUserVO updateRoles(Long id, UpdateRolesRequest request) {
        List<String> roles = normalizeRoles(request.getRoles());
        User target = getByIdOrThrow(id);
        if (target.getId().equals(AuthContextHolder.get().getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能修改自己的角色");
        }
        // 防御性兜底：单管理员 + 自锁前提下此分支实际不可达，仅防并发或数据异常导致系统无管理员
        if (parseRoles(target.getRoles()).contains("ADMIN")
                && !roles.contains("ADMIN")
                && userMapper.count(null, "ADMIN", null) <= 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "系统至少保留一名管理员");
        }
        userMapper.updateRoles(id, String.join(",", roles));
        return toVO(getByIdOrThrow(id));
    }

    @Transactional
    public AdminUserVO updateStatus(Long id, UpdateStatusRequest request) {
        Integer status = request.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态不合法");
        }
        User target = getByIdOrThrow(id);
        if (target.getId().equals(AuthContextHolder.get().getId()) && status == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能禁用自己");
        }
        // 防御性兜底：单管理员 + 自锁前提下此分支实际不可达，仅防并发或数据异常导致系统无管理员
        if (parseRoles(target.getRoles()).contains("ADMIN")
                && status == 0
                && userMapper.count(null, "ADMIN", null) <= 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "系统至少保留一名管理员");
        }
        userMapper.updateStatus(id, status);
        return toVO(getByIdOrThrow(id));
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        User target = getByIdOrThrow(id);
        if (target.getId().equals(AuthContextHolder.get().getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能重置自己的密码");
        }
        userMapper.updatePassword(id, passwordEncoder.encode(request.getNewPassword()));
    }

    /** 校验并去重角色列表：每项 trim 后必须是系统角色，任一违反抛参数错误。 */
    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色不合法");
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String role : roles) {
            String trimmed = role == null ? "" : role.trim();
            if (!VALID_ROLES.contains(trimmed)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "角色不合法");
            }
            deduped.add(trimmed);
        }
        return List.copyOf(deduped);
    }

    private User getByIdOrThrow(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private AdminUserVO toVO(User user) {
        return AdminUserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .roles(parseRoles(user.getRoles()))
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private List<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
