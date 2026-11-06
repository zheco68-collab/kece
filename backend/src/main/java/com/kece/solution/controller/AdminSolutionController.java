package com.kece.solution.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.solution.dto.SolutionDetailVO;
import com.kece.solution.dto.SolutionListItemVO;
import com.kece.solution.dto.UpdateSolutionStatusRequest;
import com.kece.solution.service.SolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅管理员可用的发布后题解管理接口。 */
@Tag(name = "管理后台-题解", description = "查询、隐藏与恢复已发布题解")
@RestController
@RequestMapping("/admin/solutions")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class AdminSolutionController {

    private final SolutionService solutionService;

    @Operation(summary = "分页查询题解，支持标题或作者、题号、题型和状态筛选")
    @GetMapping
    public ApiResponse<PageResult<SolutionListItemVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long questionId,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                solutionService.listAdmin(keyword, questionId, questionType, status, page, size));
    }

    @Operation(summary = "读取题解管理详情")
    @GetMapping("/{id}")
    public ApiResponse<SolutionDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(solutionService.getAdminDetail(id));
    }

    @Operation(summary = "隐藏或恢复题解")
    @PatchMapping("/{id}/status")
    public ApiResponse<SolutionDetailVO> updateStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateSolutionStatusRequest request) {
        return ApiResponse.success(solutionService.updateStatus(id, request));
    }
}
