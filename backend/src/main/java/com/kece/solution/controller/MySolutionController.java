package com.kece.solution.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.solution.dto.SolutionListItemVO;
import com.kece.solution.service.SolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户自己的题解列表。 */
@Tag(name = "题解", description = "当前用户题解管理")
@RestController
@RequestMapping("/me/solutions")
@RequireRole
@RequiredArgsConstructor
public class MySolutionController {

    private final SolutionService solutionService;

    @Operation(summary = "分页读取我的题解")
    @GetMapping
    public ApiResponse<PageResult<SolutionListItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(solutionService.listMine(page, size));
    }
}
