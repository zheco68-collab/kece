package com.kece.question.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.question.dto.QuestionRequest;
import com.kece.question.dto.QuestionVO;
import com.kece.question.dto.TestCaseRequest;
import com.kece.question.dto.TestCaseVO;
import com.kece.question.entity.Question;
import com.kece.question.entity.TestCase;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 算法题库业务逻辑。 */
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final TestCaseMapper testCaseMapper;
    private final SubmissionMapper submissionMapper;
    private final UserMapper userMapper;
    private final TestCaseStorageService testCaseStorageService;

    public PageResult<QuestionVO> list(
            String keyword, String difficulty, int page, int size, boolean all) {
        return list(keyword, difficulty, null, page, size, all);
    }

    public PageResult<QuestionVO> list(
            String keyword, String difficulty, String userStatus, int page, int size, boolean all) {
        PageParam p = PageParam.of(page, size);
        Integer status = (all && isPrivileged()) ? null : 1;

        List<Long> includeIds = null;
        List<Long> excludeIds = null;
        if (userStatus != null && !userStatus.isBlank()) {
            Long userId = AuthContextHolder.currentUserId();
            if (userId == null) {
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    return new PageResult<>(List.of(), 0, p.page(), p.size());
                }
            } else {
                List<Long> solvedIds = submissionMapper.findAcceptedQuestionIds(userId);
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds == null || solvedIds.isEmpty()) {
                        return new PageResult<>(List.of(), 0, p.page(), p.size());
                    }
                    includeIds = solvedIds;
                } else if ("UNSOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds != null && !solvedIds.isEmpty()) {
                        excludeIds = solvedIds;
                    }
                }
            }
        }

        List<Question> questions =
                questionMapper.findPage(
                        keyword, difficulty, status, null, includeIds, excludeIds, p.offset(), p.size());
        long total = questionMapper.count(keyword, difficulty, status, null, includeIds, excludeIds);
        return new PageResult<>(
                questions.stream().map(this::toListVO).toList(), total, p.page(), p.size());
    }

    /** 后台算法题列表：ADMIN 可按 createdBy 筛选，非 ADMIN 仅见自己出的题。 */
    public PageResult<QuestionVO> listAdmin(
            String keyword, String difficulty, Integer status, Long createdBy, int page, int size) {
        PageParam p = PageParam.of(page, size);
        Long targetCreatedBy = createdBy;
        AuthUser user = AuthContextHolder.get();
        if (user == null || !user.hasRole(Role.ADMIN)) {
            targetCreatedBy = AuthContextHolder.currentUserId();
        }
        List<Question> questions =
                questionMapper.findPage(
                        keyword, difficulty, status, targetCreatedBy, null, null, p.offset(), p.size());
        long total = questionMapper.count(keyword, difficulty, status, targetCreatedBy, null, null);
        return new PageResult<>(
                questions.stream().map(this::toListVO).toList(), total, p.page(), p.size());
    }

    public QuestionVO detail(Long id) {
        Question question = getQuestionOrThrow(id);
        if (question.getStatus() != 1 && !canManage(question)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return toVO(question);
    }

    @Transactional
    public QuestionVO create(QuestionRequest request) {
        Question question = new Question();
        applyRequest(question, request);
        question.setCreatedBy(AuthContextHolder.currentUserId());
        questionMapper.insert(question);
        return toVO(question);
    }

    @Transactional
    public QuestionVO update(Long id, QuestionRequest request) {
        Question question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        applyRequest(question, request);
        questionMapper.update(question);
        return toVO(question);
    }

    /** 删除编程题：无提交记录时出题人/管理员可删；有提交记录时仅 ADMIN 可强制删除（级联清理提交记录）。 */
    @Transactional
    public void delete(Long id) {
        Question question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        AuthUser user = AuthContextHolder.get();
        boolean admin = user != null && user.hasRole(Role.ADMIN);
        if (submissionMapper.existsByQuestionId(id) && !admin) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题目已有提交记录，仅管理员可强制删除");
        }
        if (admin) {
            submissionMapper.deleteByQuestionId(id);
        }
        testCaseMapper.deleteByQuestionId(id);
        questionMapper.deleteById(id);
    }

    public com.kece.question.dto.TestCaseSummaryVO getTestCaseSummary(Long questionId) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        com.kece.question.dto.TestCaseSummaryVO summary = testCaseMapper.getSummaryByQuestionId(questionId);
        if (summary == null) {
            return com.kece.question.dto.TestCaseSummaryVO.builder()
                    .totalCount(0)
                    .sampleCount(0)
                    .totalInputBytes(0L)
                    .totalOutputBytes(0L)
                    .totalBytes(0L)
                    .build();
        }
        return summary;
    }

    public List<TestCaseVO> listTestCases(Long questionId) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        return testCaseMapper.findByQuestionId(questionId).stream()
                .map(this::toTestCaseMetadataVO)
                .toList();
    }

    @Transactional
    public TestCaseVO addTestCase(Long questionId, TestCaseRequest request) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        List<TestCase> existing = testCaseMapper.findByQuestionId(questionId);
        int nextNum = existing.isEmpty() ? 1 : (existing.get(existing.size() - 1).getCaseNum() == null ? existing.size() + 1 : existing.get(existing.size() - 1).getCaseNum() + 1);

        TestCase testCase = new TestCase();
        testCase.setQuestionId(questionId);
        testCase.setCaseNum(request.getCaseNum() != null ? request.getCaseNum() : nextNum);
        testCase.setScoreWeight(request.getScoreWeight() != null ? request.getScoreWeight() : 10);
        testCase.setIsSample(request.getIsSample() == null ? 0 : request.getIsSample());
        testCase.setInput(request.getInput());
        testCase.setExpectedOutput(request.getExpectedOutput());

        testCaseMapper.insert(testCase);
        testCaseStorageService.writeCaseFiles(testCase, request.getInput(), request.getExpectedOutput());
        if (java.util.Objects.equals(testCase.getIsSample(), 0) && (testCase.getInputSize() > 2048 || testCase.getOutputSize() > 2048)) {
            testCase.setInput(null);
            testCase.setExpectedOutput(null);
        }
        testCaseMapper.update(testCase);
        return toTestCaseVO(testCase);
    }

    @Transactional
    public TestCaseVO updateTestCase(Long testCaseId, TestCaseRequest request) {
        TestCase testCase = getTestCaseOrThrow(testCaseId);
        Question question = getQuestionOrThrow(testCase.getQuestionId());
        checkOwnerOrAdmin(question);
        if (request.getCaseNum() != null) {
            testCase.setCaseNum(request.getCaseNum());
        }
        if (request.getScoreWeight() != null) {
            testCase.setScoreWeight(request.getScoreWeight());
        }
        if (request.getIsSample() != null) {
            testCase.setIsSample(request.getIsSample());
        }
        testCase.setInput(request.getInput());
        testCase.setExpectedOutput(request.getExpectedOutput());

        testCaseStorageService.writeCaseFiles(testCase, request.getInput(), request.getExpectedOutput());
        if (java.util.Objects.equals(testCase.getIsSample(), 0) && (testCase.getInputSize() > 2048 || testCase.getOutputSize() > 2048)) {
            testCase.setInput(null);
            testCase.setExpectedOutput(null);
        }
        testCaseMapper.update(testCase);
        return toTestCaseVO(testCase);
    }

    @Transactional
    public void toggleTestCaseSample(Long questionId, Long testCaseId, boolean isSample) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        TestCase testCase = getTestCaseOrThrow(testCaseId);
        if (!testCase.getQuestionId().equals(questionId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "测试点不属于该题目");
        }
        testCase.setIsSample(isSample ? 1 : 0);
        if (isSample) {
            testCase.setInput(testCaseStorageService.readInputText(testCase));
            testCase.setExpectedOutput(testCaseStorageService.readOutputText(testCase));
        } else {
            testCase.setInput(null);
            testCase.setExpectedOutput(null);
        }
        testCaseMapper.update(testCase);
    }

    @Transactional
    public void setSampleCaseIds(Long questionId, List<Long> sampleCaseIds) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        List<TestCase> cases = testCaseMapper.findByQuestionId(questionId);
        Set<Long> targetIds = sampleCaseIds != null ? new HashSet<>(sampleCaseIds) : Collections.emptySet();
        for (TestCase tc : cases) {
            boolean shouldBeSample = targetIds.contains(tc.getId());
            int newVal = shouldBeSample ? 1 : 0;
            if (!Objects.equals(tc.getIsSample(), newVal)) {
                tc.setIsSample(newVal);
                if (shouldBeSample) {
                    tc.setInput(testCaseStorageService.readInputText(tc));
                    tc.setExpectedOutput(testCaseStorageService.readOutputText(tc));
                } else {
                    tc.setInput(null);
                    tc.setExpectedOutput(null);
                }
                testCaseMapper.update(tc);
            }
        }
    }

    @Transactional
    public void deleteTestCase(Long testCaseId) {
        TestCase testCase = getTestCaseOrThrow(testCaseId);
        Question question = getQuestionOrThrow(testCase.getQuestionId());
        checkOwnerOrAdmin(question);
        if (testCase.getInputPath() != null) {
            java.nio.file.Path p = testCaseStorageService.resolvePath(testCase.getInputPath());
            if (p != null) {
                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
        }
        if (testCase.getOutputPath() != null) {
            java.nio.file.Path p = testCaseStorageService.resolvePath(testCase.getOutputPath());
            if (p != null) {
                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
        }
        testCaseMapper.deleteById(testCaseId);
    }

    @Transactional
    public List<TestCaseVO> importTestCasesZip(Long questionId, org.springframework.web.multipart.MultipartFile file, boolean replace, boolean firstAsSample) {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        List<TestCase> imported = testCaseStorageService.importZip(questionId, file, replace, firstAsSample);
        return imported.stream().map(this::toTestCaseVO).toList();
    }

    public void exportTestCasesZip(Long questionId, java.util.zip.ZipOutputStream zos) throws java.io.IOException {
        Question question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        testCaseStorageService.exportZip(questionId, zos);
    }

    public org.springframework.core.io.Resource getTestCaseInputResource(Long testCaseId) {
        TestCase testCase = getTestCaseOrThrow(testCaseId);
        Question question = getQuestionOrThrow(testCase.getQuestionId());
        checkOwnerOrAdmin(question);
        return testCaseStorageService.getInputResource(testCase);
    }

    public org.springframework.core.io.Resource getTestCaseOutputResource(Long testCaseId) {
        TestCase testCase = getTestCaseOrThrow(testCaseId);
        Question question = getQuestionOrThrow(testCase.getQuestionId());
        checkOwnerOrAdmin(question);
        return testCaseStorageService.getOutputResource(testCase);
    }

    private Question getQuestionOrThrow(Long id) {
        Question question = questionMapper.findById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private TestCase getTestCaseOrThrow(Long id) {
        TestCase testCase = testCaseMapper.findById(id);
        if (testCase == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "测试用例不存在");
        }
        return testCase;
    }

    private boolean canManage(Question question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        return question.getCreatedBy().equals(user.getId());
    }

    private void checkOwnerOrAdmin(Question question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN) || question.getCreatedBy().equals(user.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private boolean isPrivileged() {
        AuthUser user = AuthContextHolder.get();
        return user != null && (user.hasRole(Role.TEACHER) || user.hasRole(Role.ADMIN));
    }

    private void applyRequest(Question question, QuestionRequest request) {
        question.setTitle(request.getTitle());
        question.setDescription(request.getDescription());
        question.setDifficulty(request.getDifficulty());
        question.setTimeLimit(request.getTimeLimit() == null ? 1000 : request.getTimeLimit());
        question.setMemoryLimit(request.getMemoryLimit() == null ? 256 : request.getMemoryLimit());
        question.setLanguages(
                request.getLanguages() == null ? "cpp,java,python" : request.getLanguages());
        question.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    private QuestionVO toListVO(Question question) {
        return QuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .difficulty(question.getDifficulty())
                .timeLimit(question.getTimeLimit())
                .memoryLimit(question.getMemoryLimit())
                .languages(splitLanguages(question.getLanguages()))
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private QuestionVO toVO(Question question) {
        List<TestCaseVO> samples =
                testCaseMapper.findSamplesByQuestionId(question.getId()).stream()
                        .map(this::toSampleTestCaseVO)
                        .toList();
        User author = question.getCreatedBy() != null ? userMapper.findById(question.getCreatedBy()) : null;
        return QuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .description(question.getDescription())
                .difficulty(question.getDifficulty())
                .timeLimit(question.getTimeLimit())
                .memoryLimit(question.getMemoryLimit())
                .languages(splitLanguages(question.getLanguages()))
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .authorName(author != null ? author.getUsername() : null)
                .authorAvatarUrl(author != null ? author.getAvatarUrl() : null)
                .sampleTestCases(samples)
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private List<String> splitLanguages(String languages) {
        if (languages == null || languages.isBlank()) {
            return List.of();
        }
        return Arrays.stream(languages.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private TestCaseVO toTestCaseMetadataVO(TestCase testCase) {
        long inSize = testCase.getInputSize() != null ? testCase.getInputSize() : 0L;
        long outSize = testCase.getOutputSize() != null ? testCase.getOutputSize() : 0L;
        boolean isLarge = inSize > TestCaseStorageService.MAX_PREVIEW_BYTES || outSize > TestCaseStorageService.MAX_PREVIEW_BYTES;

        return TestCaseVO.builder()
                .id(testCase.getId())
                .questionId(testCase.getQuestionId())
                .caseNum(testCase.getCaseNum() != null ? testCase.getCaseNum() : 1)
                .inputPath(testCase.getInputPath())
                .outputPath(testCase.getOutputPath())
                .inputSize(inSize)
                .outputSize(outSize)
                .scoreWeight(testCase.getScoreWeight() != null ? testCase.getScoreWeight() : 10)
                .input(null)
                .expectedOutput(null)
                .isSample(testCase.getIsSample())
                .isLarge(isLarge)
                .build();
    }

    private TestCaseVO toTestCaseVO(TestCase testCase) {
        return toTestCaseMetadataVO(testCase);
    }

    private TestCaseVO toSampleTestCaseVO(TestCase testCase) {
        long inSize = testCase.getInputSize() != null ? testCase.getInputSize() : 0L;
        long outSize = testCase.getOutputSize() != null ? testCase.getOutputSize() : 0L;
        boolean isLarge = inSize > TestCaseStorageService.MAX_PREVIEW_BYTES || outSize > TestCaseStorageService.MAX_PREVIEW_BYTES;

        return TestCaseVO.builder()
                .id(testCase.getId())
                .questionId(testCase.getQuestionId())
                .caseNum(testCase.getCaseNum() != null ? testCase.getCaseNum() : 1)
                .inputPath(testCase.getInputPath())
                .outputPath(testCase.getOutputPath())
                .inputSize(inSize)
                .outputSize(outSize)
                .scoreWeight(testCase.getScoreWeight() != null ? testCase.getScoreWeight() : 10)
                .input(testCaseStorageService.readInputText(testCase))
                .expectedOutput(testCaseStorageService.readOutputText(testCase))
                .isSample(testCase.getIsSample())
                .isLarge(isLarge)
                .build();
    }
}
