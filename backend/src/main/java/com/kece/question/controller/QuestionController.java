package com.kece.question.controller;

import com.kece.common.response.ApiResponse;
import com.kece.common.response.PageResult;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.question.dto.QuestionRequest;
import com.kece.question.dto.QuestionVO;
import com.kece.question.dto.TestCaseRequest;
import com.kece.question.dto.TestCaseVO;
import com.kece.question.service.QuestionService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 算法题库接口。 */
@Tag(name = "算法题库")
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @Operation(summary = "题库列表（分页/筛选）")
    @GetMapping
    public ApiResponse<PageResult<QuestionVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String userStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success(questionService.list(keyword, difficulty, userStatus, page, size, all));
    }

    @Operation(summary = "题目详情")
    @GetMapping("/{id}")
    public ApiResponse<QuestionVO> detail(@PathVariable Long id) {
        return ApiResponse.success(questionService.detail(id));
    }

    @Operation(summary = "创建题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping
    public ApiResponse<QuestionVO> create(@Valid @RequestBody QuestionRequest request) {
        return ApiResponse.success(questionService.create(request));
    }

    @Operation(summary = "更新题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PutMapping("/{id}")
    public ApiResponse<QuestionVO> update(
            @PathVariable Long id, @Valid @RequestBody QuestionRequest request) {
        return ApiResponse.success(questionService.update(id, request));
    }

    @Operation(summary = "删除题目")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "题目测试用例轻量统计汇总（出题人/管理员）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/{id}/test-cases/summary")
    public ApiResponse<com.kece.question.dto.TestCaseSummaryVO> getTestCaseSummary(@PathVariable Long id) {
        return ApiResponse.success(questionService.getTestCaseSummary(id));
    }

    @Operation(summary = "题目测试用例列表（出题人/管理员）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/{id}/test-cases")
    public ApiResponse<List<TestCaseVO>> listTestCases(@PathVariable Long id) {
        return ApiResponse.success(questionService.listTestCases(id));
    }

    @Operation(summary = "新增测试用例")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping("/{id}/test-cases")
    public ApiResponse<TestCaseVO> addTestCase(
            @PathVariable Long id, @Valid @RequestBody TestCaseRequest request) {
        return ApiResponse.success(questionService.addTestCase(id, request));
    }

    @Operation(summary = "更新测试用例")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PutMapping("/test-cases/{testCaseId}")
    public ApiResponse<TestCaseVO> updateTestCase(
            @PathVariable Long testCaseId, @Valid @RequestBody TestCaseRequest request) {
        return ApiResponse.success(questionService.updateTestCase(testCaseId, request));
    }

    @Operation(summary = "删除测试用例")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @DeleteMapping("/test-cases/{testCaseId}")
    public ApiResponse<Void> deleteTestCase(@PathVariable Long testCaseId) {
        questionService.deleteTestCase(testCaseId);
        return ApiResponse.success();
    }

    @Operation(summary = "批量导入测试点（Zip 压缩包）")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @PostMapping(value = "/{id}/test-cases/import-zip", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<TestCaseVO>> importZip(
            @PathVariable Long id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "replace", defaultValue = "false") boolean replace,
            @RequestParam(value = "firstAsSample", defaultValue = "true") boolean firstAsSample) {
        return ApiResponse.success(questionService.importTestCasesZip(id, file, replace, firstAsSample));
    }

    @Operation(summary = "导出题目测试集为 Zip 压缩包")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/{id}/test-cases/export-zip")
    public void exportZip(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("application/zip");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"testcases_q" + id + ".zip\"");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            questionService.exportTestCasesZip(id, zos);
            zos.finish();
        }
    }

    @Operation(summary = "下载单个测试点输入文件")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/test-cases/{testCaseId}/input")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadInput(@PathVariable Long testCaseId) {
        org.springframework.core.io.Resource resource = questionService.getTestCaseInputResource(testCaseId);
        if (resource == null || !resource.exists()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + testCaseId + ".in\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @Operation(summary = "下载单个测试点输出文件")
    @RequireRole({Role.TEACHER, Role.ADMIN})
    @GetMapping("/test-cases/{testCaseId}/output")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadOutput(@PathVariable Long testCaseId) {
        org.springframework.core.io.Resource resource = questionService.getTestCaseOutputResource(testCaseId);
        if (resource == null || !resource.exists()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + testCaseId + ".out\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
