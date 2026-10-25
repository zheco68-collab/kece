package com.kece.system.controller;

import com.kece.common.response.ApiResponse;
import com.kece.system.dto.ClusterStatusVO;
import com.kece.system.dto.DemoSnippetVO;
import com.kece.system.service.SystemDemoSnippetService;
import com.kece.system.service.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统级运行状态与评测机节点遥测 API。
 * 无需鉴权（前台首页、监控大屏公开展示集群健康状态与演示代码）。
 */
@RestController
@RequestMapping("/system")
public class SystemStatusController {

    private final SystemStatusService systemStatusService;
    private final SystemDemoSnippetService systemDemoSnippetService;

    public SystemStatusController(SystemStatusService systemStatusService, SystemDemoSnippetService systemDemoSnippetService) {
        this.systemStatusService = systemStatusService;
        this.systemDemoSnippetService = systemDemoSnippetService;
    }

    /**
     * 获取当前评测机集群与各节点状态（空闲 / 测评中 / 掉线）。
     */
    @GetMapping("/judgers")
    public ApiResponse<ClusterStatusVO> getJudgersStatus() {
        return ApiResponse.success(systemStatusService.getClusterStatus());
    }

    /**
     * 获取首页演示区随机 Java 算法模板代码（从 GitHub 异步缓存并高可靠保底）。
     */
    @GetMapping("/demo-snippet")
    public ApiResponse<DemoSnippetVO> getDemoSnippet() {
        return ApiResponse.success(systemDemoSnippetService.getCurrentSnippet());
    }
}
