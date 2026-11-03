package com.kece.database.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.database.dto.DbQuestionRequest;
import com.kece.database.dto.DbQuestionVO;
import com.kece.database.dto.DbSubmitRequest;
import com.kece.database.service.DbQuestionService;
import com.kece.database.service.DbSubmissionService;
import com.kece.submission.dto.SubmissionVO;
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

/** 数据库题目接口。 */
@Tag(name = "数据库 OJ")
@RestController
@RequestMapping("/db-questions")
@RequiredArgsConstructor
public class DbQuestionController {

    private final DbQuestionService questionService;
    private final DbSubmissionService submissionService;

    @Operation(summary = "数据库题目列表（分页/筛选）")
    @GetMapping
    public ApiResponse<PageResult<DbQuestionVO>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success(questionService.list(type, keyword, userStatus, page, size, all));
    }

    @Operation(summary = "数据库题目详情")
    @GetMapping("/{id}")
    public ApiResponse<DbQuestionVO> detail(
            @PathVariable Long id,
            @RequestParam(required = false) String practiceCode) {
        return ApiResponse.success(questionService.detail(id, practiceCode));
    }

    @Operation(summary = "创建数据库题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping
    public ApiResponse<DbQuestionVO> create(@Valid @RequestBody DbQuestionRequest request) {
        return ApiResponse.success(questionService.create(request));
    }

    @Operation(summary = "更新数据库题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PutMapping("/{id}")
    public ApiResponse<DbQuestionVO> update(
            @PathVariable Long id, @Valid @RequestBody DbQuestionRequest request) {
        return ApiResponse.success(questionService.update(id, request));
    }

    @Operation(summary = "删除数据库题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "提交 SQL（异步评测）")
    @RequireRole
    @PostMapping("/{id}/submit")
    public ApiResponse<SubmissionVO> submit(
            @PathVariable Long id, @Valid @RequestBody DbSubmitRequest request) {
        return ApiResponse.success(
                submissionService.submit(
                        id,
                        request.getSql(),
                        request.getPracticeCode(),
                        request.getPracticeQuestionId()));
    }
}
