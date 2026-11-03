package com.kece.practice.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.practice.dto.*;
import com.kece.practice.service.LessonPracticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "随堂课练模块")
@RestController
@RequestMapping("/practice")
@RequiredArgsConstructor
public class LessonPracticeController {

    private final LessonPracticeService practiceService;

    @Operation(summary = "发起课练（仅教师与管理员）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping
    public ApiResponse<PracticeSummaryVO> createPractice(
            @Valid @RequestBody CreatePracticeRequest req) {
        Long teacherId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.createPractice(teacherId, req));
    }

    @Operation(summary = "课练列表分页查询（前台大厅）")
    @GetMapping
    public ApiResponse<Map<String, Object>> listPractices(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean joinedOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        Long userId = AuthContextHolder.currentUserId();
        return ApiResponse.success(
                practiceService.listPractices(
                        keyword, status, null, userId, false, joinedOnly, page, size));
    }

    @Operation(summary = "教师/管理后台课练列表（非 ADMIN 仅见自己的）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/admin")
    public ApiResponse<Map<String, Object>> listAdmin(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthUser user = AuthContextHolder.get();
        Long teacherId =
                (user != null && user.hasRole(Role.ADMIN))
                        ? null
                        : (user != null ? user.getId() : null);
        Long currentUserId = user != null ? user.getId() : null;
        return ApiResponse.success(
                practiceService.listPractices(
                        keyword, status, teacherId, currentUserId, true, false, page, size));
    }

    @Operation(summary = "通过6位口令加入课练")
    @RequireRole
    @PostMapping("/join")
    public ApiResponse<PracticeDetailVO> joinPractice(@RequestBody Map<String, Object> body) {
        Long userId = AuthContextHolder.currentUserId();
        String code = body.get("accessCode") != null ? body.get("accessCode").toString() : null;
        Long expectedPracticeId = null;
        if (body.get("practiceId") != null && !body.get("practiceId").toString().isBlank()) {
            expectedPracticeId = Long.valueOf(body.get("practiceId").toString());
        }
        return ApiResponse.success(practiceService.joinPractice(userId, code, expectedPracticeId));
    }

    @Operation(summary = "获取课练主页详情（题目概览与个人作答态，需已解锁或管理权限）")
    @RequireRole
    @GetMapping("/{code}")
    public ApiResponse<PracticeDetailVO> getDetail(@PathVariable String code) {
        Long userId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.getPracticeDetail(code, userId));
    }

    @Operation(summary = "获取正统实时成绩榜 (Scoreboard)")
    @RequireRole
    @GetMapping("/{code}/scoreboard")
    public ApiResponse<PracticeScoreboardVO> getScoreboard(@PathVariable String code) {
        Long userId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.getScoreboard(code, userId));
    }

    @Operation(summary = "客观题作答即时保存并增量判分")
    @RequireRole
    @PostMapping("/{code}/objective-submit")
    public ApiResponse<PracticeObjectiveResultVO> submitObjective(
            @PathVariable String code, @Valid @RequestBody PracticeObjectiveSubmitRequest req) {
        Long userId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.submitObjective(code, userId, req));
    }

    @Operation(summary = "实操题（代码/SQL）评测结果同步回写课练（兼容前端直接上报，幂等）")
    @RequireRole
    @PostMapping("/{code}/practical-record")
    public ApiResponse<Void> recordPractical(
            @PathVariable String code, @RequestBody Map<String, Object> body) {
        Long userId = AuthContextHolder.currentUserId();
        Long practiceQuestionId = Long.valueOf(body.get("practiceQuestionId").toString());
        Long submissionId =
                body.get("submissionId") != null
                        ? Long.valueOf(body.get("submissionId").toString())
                        : null;
        String questionType = (String) body.get("questionType");
        String status = (String) body.get("status");
        int score = body.get("score") != null ? Integer.parseInt(body.get("score").toString()) : 0;

        practiceService.recordPracticalSubmission(
                code, userId, practiceQuestionId, submissionId, questionType, status, score);
        return ApiResponse.success(null);
    }

    @Operation(summary = "学生主动交卷")
    @RequireRole
    @PostMapping("/{code}/submit")
    public ApiResponse<Void> submitPractice(@PathVariable String code) {
        Long userId = AuthContextHolder.currentUserId();
        practiceService.submitPractice(code, userId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "教师提前结束课练")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping("/{code}/end")
    public ApiResponse<Void> endPractice(@PathVariable String code) {
        Long teacherId = AuthContextHolder.currentUserId();
        practiceService.endPractice(code, teacherId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "教师延长课练时长")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping("/{code}/extend")
    public ApiResponse<Void> extendDuration(
            @PathVariable String code, @RequestBody Map<String, Integer> body) {
        Long teacherId = AuthContextHolder.currentUserId();
        int extraMinutes = body.getOrDefault("extraMinutes", 5);
        practiceService.extendDuration(code, extraMinutes, teacherId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "删除课练（教师仅能删除自己的，管理员可删全部）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePractice(@PathVariable Long id) {
        AuthUser user = AuthContextHolder.get();
        practiceService.deletePractice(id, user);
        return ApiResponse.success(null);
    }

    // ========================================================
    // 教师与管理员考务中台专有接口 (/practice/admin/**)
    // ========================================================

    @Operation(summary = "教师考务大盘数据 (Overview)")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/admin/{id}/overview")
    public ApiResponse<PracticeOverviewVO> getAdminOverview(@PathVariable Long id) {
        Long teacherId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.getOverview(id, teacherId));
    }

    @Operation(summary = "二次编辑课练配置与题集")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PutMapping("/admin/{id}")
    public ApiResponse<PracticeSummaryVO> updatePractice(
            @PathVariable Long id, @Valid @RequestBody UpdatePracticeRequest req) {
        Long teacherId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.updatePractice(id, teacherId, req));
    }

    @Operation(summary = "调阅指定学生全息作答代码快照与评测详情")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/admin/{id}/audit/{targetUserId}")
    public ApiResponse<StudentAuditDetailVO> getStudentAudit(
            @PathVariable Long id, @PathVariable Long targetUserId) {
        Long teacherId = AuthContextHolder.currentUserId();
        return ApiResponse.success(practiceService.getStudentAudit(id, targetUserId, teacherId));
    }

    @Operation(summary = "导出全班成绩单 CSV（带 UTF-8 BOM）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/admin/{id}/export-csv")
    public void exportCsv(@PathVariable Long id, HttpServletResponse response) {
        Long teacherId = AuthContextHolder.currentUserId();
        practiceService.exportScoreboardCsv(id, teacherId, response);
    }
}
