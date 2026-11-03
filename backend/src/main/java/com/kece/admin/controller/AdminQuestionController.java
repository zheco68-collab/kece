package com.kece.admin.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.database.dto.DbQuestionVO;
import com.kece.database.service.DbQuestionService;
import com.kece.question.dto.QuestionVO;
import com.kece.question.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台题目管理接口。 */
@Tag(name = "后台管理")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminQuestionController {

    private final QuestionService questionService;
    private final DbQuestionService dbQuestionService;

    @Operation(summary = "算法题列表（分页/筛选）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/questions")
    public ApiResponse<PageResult<QuestionVO>> listQuestions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                questionService.listAdmin(keyword, difficulty, status, createdBy, id, page, size));
    }

    @Operation(summary = "数据库题列表（分页/筛选）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/db-questions")
    public ApiResponse<PageResult<DbQuestionVO>> listDbQuestions(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                dbQuestionService.listAdmin(type, keyword, status, createdBy, id, page, size));
    }
}
