package com.kece.problemset.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.problemset.dto.ProblemSetDetailVO;
import com.kece.problemset.dto.ProblemSetSummaryVO;
import com.kece.problemset.service.ProblemSetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 前台题集大厅与章节闯关接口控制器。 */
@Tag(name = "题集大厅", description = "公开题集浏览、章节详情、收藏与断点续刷")
@RestController
@RequestMapping("/problem-sets")
@RequiredArgsConstructor
public class ProblemSetController {

    private final ProblemSetService problemSetService;

    @Operation(summary = "分页查询公开题集列表")
    @GetMapping
    public ApiResponse<PageResult<ProblemSetSummaryVO>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean favoritedOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.success(
                problemSetService.listPublic(
                        category, difficulty, keyword, favoritedOnly, page, size));
    }

    @Operation(summary = "获取题集详情与章节目录（登录态自动触发 AC 读自愈）")
    @GetMapping("/{id}")
    public ApiResponse<ProblemSetDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(problemSetService.getDetail(id));
    }

    @Operation(summary = "收藏 / 取消收藏题集")
    @RequireRole
    @PostMapping("/{id}/favorite")
    public ApiResponse<Map<String, Object>> toggleFavorite(@PathVariable Long id) {
        return ApiResponse.success(problemSetService.toggleFavorite(id));
    }

    @Operation(summary = "记录最近练习的题集条目（断点续刷定位）")
    @RequireRole
    @PostMapping("/{id}/touch")
    public ApiResponse<Void> touch(@PathVariable Long id, @RequestParam Long itemId) {
        problemSetService.touchLastItem(id, itemId);
        return ApiResponse.success();
    }
}
