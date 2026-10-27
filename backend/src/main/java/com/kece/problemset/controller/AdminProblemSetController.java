package com.kece.problemset.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.problemset.dto.ProblemSetDetailVO;
import com.kece.problemset.dto.ProblemSetSummaryVO;
import com.kece.problemset.dto.SaveProblemSetRequest;
import com.kece.problemset.service.ProblemSetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 后台管理题集接口控制器（支持 ADMIN 与 TEACHER）。 */
@Tag(name = "管理后台-题集管理", description = "创建、分章节编排、更新与删除题集")
@RestController
@RequestMapping("/admin/problem-sets")
@RequireRole({Role.ADMIN, Role.TEACHER})
@RequiredArgsConstructor
public class AdminProblemSetController {

    private final ProblemSetService problemSetService;

    @Operation(summary = "后台分页查询题集列表")
    @GetMapping
    public ApiResponse<PageResult<ProblemSetSummaryVO>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "15") int size) {
        return ApiResponse.success(problemSetService.listAdmin(category, keyword, page, size));
    }

    @Operation(summary = "创建题集")
    @PostMapping
    public ApiResponse<ProblemSetDetailVO> create(@Valid @RequestBody SaveProblemSetRequest req) {
        return ApiResponse.success(problemSetService.createProblemSet(req));
    }

    @Operation(summary = "更新题集（含乐观锁校验）")
    @PutMapping("/{id}")
    public ApiResponse<ProblemSetDetailVO> update(
            @PathVariable Long id, @Valid @RequestBody SaveProblemSetRequest req) {
        return ApiResponse.success(problemSetService.updateProblemSet(id, req));
    }

    @Operation(summary = "软删除题集")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        problemSetService.deleteProblemSet(id);
        return ApiResponse.success();
    }
}
