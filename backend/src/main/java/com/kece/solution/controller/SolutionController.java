package com.kece.solution.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.solution.dto.CreateSolutionRequest;
import com.kece.solution.dto.SolutionDetailVO;
import com.kece.solution.dto.SolutionListItemVO;
import com.kece.solution.dto.UpdateSolutionRequest;
import com.kece.solution.service.SolutionService;
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

/** 前台题解列表、阅读、发布和本人编辑接口。 */
@Tag(name = "题解", description = "公开阅读与登录用户直接发布题解")
@RestController
@RequestMapping("/solutions")
@RequiredArgsConstructor
public class SolutionController {

    private final SolutionService solutionService;

    @Operation(summary = "分页读取某题的公开题解")
    @GetMapping
    public ApiResponse<PageResult<SolutionListItemVO>> list(
            @RequestParam String questionType,
            @RequestParam Long questionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                solutionService.listPublic(questionType, questionId, page, size));
    }

    @Operation(summary = "读取题解全文")
    @GetMapping("/{id}")
    public ApiResponse<SolutionDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(solutionService.getDetail(id));
    }

    @Operation(summary = "直接发布题解")
    @PostMapping
    @RequireRole
    public ApiResponse<SolutionDetailVO> create(@Valid @RequestBody CreateSolutionRequest request) {
        return ApiResponse.success(solutionService.create(request));
    }

    @Operation(summary = "编辑本人题解")
    @PutMapping("/{id}")
    @RequireRole
    public ApiResponse<SolutionDetailVO> update(
            @PathVariable Long id, @Valid @RequestBody UpdateSolutionRequest request) {
        return ApiResponse.success(solutionService.update(id, request));
    }

    @Operation(summary = "删除本人题解，管理员也可删除")
    @DeleteMapping("/{id}")
    @RequireRole
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestParam Long version) {
        solutionService.delete(id, version);
        return ApiResponse.success();
    }
}
