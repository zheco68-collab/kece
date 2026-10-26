package com.kece.user.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.security.RequireRole;
import com.kece.user.dto.ChangePasswordRequest;
import com.kece.user.dto.HeatmapVO;
import com.kece.user.dto.UpdateProfileRequest;
import com.kece.user.dto.UserExamRecordVO;
import com.kece.user.dto.UserProfileVO;
import com.kece.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 个人中心与用户资料接口。 */
@Tag(name = "个人中心")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "获取用户资料（默认当前用户，可指定 userId 查看他人）")
    @RequireRole
    @GetMapping("/profile")
    public ApiResponse<UserProfileVO> getProfile(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(profileService.getProfile(userId));
    }

    @Operation(summary = "更新个人名片（签名与社交链接）")
    @RequireRole
    @PutMapping("/profile")
    public ApiResponse<UserProfileVO> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(profileService.updateProfile(request));
    }

    @Operation(summary = "上传本地头像图片")
    @RequireRole
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String avatarUrl = profileService.uploadAvatar(file);
        return ApiResponse.success(Map.of("avatarUrl", avatarUrl));
    }

    @Operation(summary = "修改登录密码")
    @RequireRole
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(request);
        return ApiResponse.success();
    }

    @Operation(summary = "获取近一年 52 周 AC 活跃度打卡热力图")
    @RequireRole
    @GetMapping("/heatmap")
    public ApiResponse<HeatmapVO> getHeatmap(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(profileService.getHeatmap(userId));
    }

    @Operation(summary = "获取用户考试战绩档案")
    @RequireRole
    @GetMapping("/exams")
    public ApiResponse<List<UserExamRecordVO>> getExams(
            @RequestParam(required = false) Long userId) {
        return ApiResponse.success(profileService.getExams(userId));
    }
}
