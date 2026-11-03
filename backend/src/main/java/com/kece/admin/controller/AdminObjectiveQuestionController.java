package com.kece.admin.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.objective.dto.ObjectiveQuestionVO;
import com.kece.objective.service.ObjectiveQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台客观题管理接口。 */
@Tag(name = "后台管理")
@RestController
@RequestMapping("/admin/objective-questions")
@RequiredArgsConstructor
public class AdminObjectiveQuestionController {

    private final ObjectiveQuestionService objectiveQuestionService;

    @Operation(summary = "客观题列表（管理）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping
    public ApiResponse<PageResult<ObjectiveQuestionVO>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                objectiveQuestionService.listAdmin(
                        type, keyword, status, createdBy, id, page, size));
    }
}
