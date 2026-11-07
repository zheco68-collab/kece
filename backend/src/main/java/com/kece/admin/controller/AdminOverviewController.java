package com.kece.admin.controller;

import com.kece.admin.dto.AdminOverviewActivities;
import com.kece.admin.dto.AdminSubmissionTrend;
import com.kece.admin.service.AdminOverviewService;
import com.kece.common.response.ApiResponse;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/overview")
@RequireRole({Role.TEACHER, Role.ADMIN})
@RequiredArgsConstructor
public class AdminOverviewController {
    private final AdminOverviewService overviewService;

    @GetMapping("/activities")
    public ResponseEntity<ApiResponse<AdminOverviewActivities>> activities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(overviewService.activities()));
    }

    @GetMapping("/submission-trend")
    public ResponseEntity<ApiResponse<AdminSubmissionTrend>> submissionTrend(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(overviewService.submissionTrend(days)));
    }
}
