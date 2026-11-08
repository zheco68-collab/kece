package com.kece.exam.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.exam.dto.ExamAnswerRequest;
import com.kece.exam.dto.ExamRankVO;
import com.kece.exam.dto.ExamSessionVO;
import com.kece.exam.dto.ExamSubmissionVO;
import com.kece.exam.dto.SessionEnterVO;
import com.kece.exam.dto.SingleSubmitVO;
import com.kece.exam.dto.SubmitResultVO;
import com.kece.exam.dto.SwitchCountVO;
import com.kece.exam.dto.SwitchReportRequest;
import com.kece.exam.service.ExamAntiCheatService;
import com.kece.exam.service.ExamRankService;
import com.kece.exam.service.ExamService;
import com.kece.exam.service.ExamSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 考试作答接口（考生进入/作答/交卷，管理取消）。考试本身即一次考试，无场次中间层。 */
@Tag(name = "考试作答")
@RestController
@RequestMapping("/exams")
@RequiredArgsConstructor
public class ExamSessionController {

    private final ExamService examService;
    private final ExamSubmissionService examSubmissionService;
    private final ExamRankService examRankService;
    private final ExamAntiCheatService antiCheatService;

    @Operation(summary = "公开可进入考试列表（公开且未结束/未取消）")
    @RequireRole
    @GetMapping("/open")
    public ApiResponse<PageResult<ExamSessionVO>> listOpen(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(examService.listOpen(keyword, page, size));
    }

    @Operation(summary = "进入考试（返回倒计时与题目快照；到点自动锁定）")
    @RequireRole
    @GetMapping("/{id}/enter")
    public ApiResponse<SessionEnterVO> enter(@PathVariable Long id) {
        return ApiResponse.success(examService.enter(id));
    }

    @Operation(summary = "考生登记实名身份信息（进入要求登记信息的考试时调用）")
    @RequireRole
    @PostMapping("/{id}/student-info")
    public ApiResponse<com.kece.exam.dto.ExamStudentInfoVO> saveStudentInfo(
            @PathVariable Long id,
            @Valid @RequestBody com.kece.exam.dto.ExamStudentInfoRequest request) {
        return ApiResponse.success(examService.saveStudentInfo(id, request));
    }

    @Operation(summary = "查询考生在本次考试中的实名登记信息")
    @RequireRole
    @GetMapping("/{id}/student-info")
    public ApiResponse<com.kece.exam.dto.ExamStudentInfoVO> getStudentInfo(@PathVariable Long id) {
        return ApiResponse.success(examService.getStudentInfo(id));
    }

    @Operation(summary = "保存作答（UPSERT，已交卷的题目不可再改）")
    @RequireRole
    @PutMapping("/{id}/answers")
    public ApiResponse<Void> saveAnswer(
            @PathVariable Long id,
            @Valid @RequestBody ExamAnswerRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        examSubmissionService.saveAnswer(id, request, key);
        return ApiResponse.success();
    }

    @Operation(summary = "ACM 赛制单题实时提交（OI 赛制与客观题拒绝，客观题交卷后统一判分）")
    @RequireRole
    @PostMapping("/{id}/questions/{examQuestionId}/submit")
    public ApiResponse<SingleSubmitVO> submitQuestion(
            @PathVariable Long id,
            @PathVariable Long examQuestionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.success(examSubmissionService.submitQuestion(id, examQuestionId, key));
    }

    @Operation(summary = "交卷评测（客观题同步判分；代码/数据库题批量入队；幂等）")
    @RequireRole
    @PostMapping("/{id}/submit")
    public ApiResponse<SubmitResultVO> submit(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.success(examSubmissionService.submit(id, key));
    }

    @Operation(summary = "我的作答与成绩")
    @RequireRole
    @GetMapping("/{id}/results")
    public ApiResponse<List<ExamSubmissionVO>> results(@PathVariable Long id) {
        return ApiResponse.success(examSubmissionService.myResults(id));
    }

    @Operation(summary = "考试排名（ACM：AC 数+罚时 / OI：总分）")
    @RequireRole
    @GetMapping("/{id}/ranking")
    public ApiResponse<List<ExamRankVO>> ranking(@PathVariable Long id) {
        return ApiResponse.success(examRankService.ranking(id));
    }

    @Operation(summary = "上报切屏（仅考试进行中计数；返回最新累计次数）")
    @RequireRole
    @PostMapping("/{id}/switch")
    public ApiResponse<SwitchCountVO> reportSwitch(
            @PathVariable Long id, @Valid @RequestBody SwitchReportRequest request) {
        int delta = request.getDelta() == null ? 1 : request.getDelta();
        return ApiResponse.success(antiCheatService.reportSwitch(id, delta));
    }

    @Operation(summary = "查询本人切屏计数")
    @RequireRole
    @GetMapping("/{id}/switch")
    public ApiResponse<SwitchCountVO> mySwitchCount(@PathVariable Long id) {
        return ApiResponse.success(antiCheatService.mySwitchCount(id));
    }

    @Operation(summary = "取消考试")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @DeleteMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        examService.cancel(id);
        return ApiResponse.success();
    }
}
