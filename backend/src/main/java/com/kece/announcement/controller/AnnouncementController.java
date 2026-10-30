package com.kece.announcement.controller;

import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.service.AnnouncementService;
import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台通知公告看板接口控制器。
 * 公开访问，无分类与模糊搜索，走二级高可用缓存架构。
 */
@Tag(name = "前台-通知看板", description = "系统通知公告展示与详情正文阅读")
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "分页查询前台看板公告列表（走 Redis 缓存）")
    @GetMapping
    public ApiResponse<PageResult<AnnouncementBoardItemVO>> listBoard(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(announcementService.listBoard(page, size));
    }

    @Operation(summary = "获取公告完整详情（阅读量自增）")
    @GetMapping("/{id}")
    public ApiResponse<AnnouncementDetailVO> getDetail(@PathVariable Long id) {
        return ApiResponse.success(announcementService.getDetail(id));
    }
}
