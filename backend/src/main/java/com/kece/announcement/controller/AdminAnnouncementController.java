package com.kece.announcement.controller;

import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.dto.AnnouncementPinnedRequest;
import com.kece.announcement.dto.AnnouncementRequest;
import com.kece.announcement.dto.AnnouncementStatusRequest;
import com.kece.announcement.service.AnnouncementService;
import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台公告管理接口控制器。
 * 权限要求：ADMIN 或 TEACHER。
 */
@Tag(name = "管理后台-公告管理", description = "公告发布、编辑、置顶、状态管理与删除")
@RestController
@RequestMapping("/admin/announcements")
@RequireRole({Role.ADMIN, Role.TEACHER})
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "管理端分页查询公告列表（无分类/无搜索，支持状态过滤）")
    @GetMapping
    public ApiResponse<PageResult<AnnouncementDetailVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "15") int size) {
        return ApiResponse.success(announcementService.listAdmin(status, page, size));
    }

    @Operation(summary = "管理端获取公告详情")
    @GetMapping("/{id}")
    public ApiResponse<AnnouncementDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(announcementService.getAdminDetail(id));
    }

    @Operation(summary = "发布新公告")
    @PostMapping
    public ApiResponse<AnnouncementDetailVO> create(@Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.success(announcementService.create(request));
    }

    @Operation(summary = "编辑公告")
    @PutMapping("/{id}")
    public ApiResponse<AnnouncementDetailVO> update(
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.success(announcementService.update(id, request));
    }

    @Operation(summary = "切换或修改公告置顶状态")
    @PutMapping("/{id}/pinned")
    public ApiResponse<AnnouncementDetailVO> updatePinned(
            @PathVariable Long id,
            @RequestBody(required = false) AnnouncementPinnedRequest request,
            @RequestParam(required = false) Integer isPinned) {
        Integer target = (request != null && request.getIsPinned() != null)
                ? request.getIsPinned()
                : isPinned;
        return ApiResponse.success(announcementService.updatePinned(id, target));
    }

    @Operation(summary = "修改公告状态（上下架/草稿）")
    @PutMapping("/{id}/status")
    public ApiResponse<AnnouncementDetailVO> updateStatus(
            @PathVariable Long id,
            @RequestBody(required = false) AnnouncementStatusRequest request,
            @RequestParam(required = false) String status) {
        String targetStatus = (request != null && request.getStatus() != null && !request.getStatus().isBlank())
                ? request.getStatus()
                : status;
        return ApiResponse.success(announcementService.updateStatus(id, targetStatus));
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        announcementService.delete(id);
        return ApiResponse.success();
    }
}
