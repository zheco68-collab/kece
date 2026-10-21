package com.kece.rank.controller;

import com.kece.common.response.ApiResponse;
import com.kece.rank.dto.RankPageVO;
import com.kece.rank.service.RankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全域排行榜接口控制器。 */
@RestController
@RequestMapping("/rankings")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    /**
     * 查询指定题型分类的排行榜列表。
     *
     * @param type 题型分类：ALGO（算法编程）、SQL（数据库）、OBJECTIVE（客观题），默认 ALGO
     * @param page 页码，默认 1
     * @param size 每页记录数，默认 50，最大 100
     * @param keyword 搜索关键字（匹配用户名）
     * @return 排行榜分页数据与当前用户本人名次卡片
     */
    @GetMapping
    public ApiResponse<RankPageVO> getRankings(
            @RequestParam(defaultValue = "ALGO") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(rankService.getRankings(type, page, size, keyword));
    }
}
