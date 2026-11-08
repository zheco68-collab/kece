package com.kece.judging.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.judging.JudgeDispatch.Kind;
import com.kece.judging.artifact.JudgeManifest.Artifact;
import com.kece.judging.artifact.JudgeManifest.CaseSpec;
import com.kece.judging.artifact.JudgeManifest.SqlSpec;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.question.service.TestCaseStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JudgeManifestService {

    private final QuestionMapper questions;
    private final DbQuestionMapper dbQuestions;
    private final TestCaseMapper cases;
    private final DbTestCaseMapper dbCases;
    private final TestCaseStorageService legacyFiles;
    private final JudgeManifestMapper manifests;
    private final JudgeArtifactStore artifacts;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Kind kind, long questionId) {
        Map<String, Artifact> referenced = new TreeMap<>();
        JudgeManifest document;
        try {
            document =
                    switch (kind) {
                        case ALGO -> codeManifest(questionId, referenced);
                        case DATABASE -> sqlManifest(questionId, referenced);
                    };
            String body = json.writeValueAsString(document);
            String digest = JudgeArtifactStore.digest(body.getBytes(StandardCharsets.UTF_8));
            Long id = manifests.findVersion(kind.name(), questionId, digest);
            if (id == null) {
                var row = new JudgeManifestMapper.ManifestRow();
                row.setType(kind.name());
                row.setQuestionId(questionId);
                row.setDigest(digest);
                row.setDocument(body);
                row.setTotalCases(document.cases().size());
                manifests.insert(row);
                id = row.getId();
                // Acquire shared artifact rows in digest order across concurrent publications.
                for (Artifact ref : referenced.values()) {
                    manifests.register(ref);
                    manifests.attach(id, ref.digest());
                }
            }
            manifests.pointTo(kind.name(), questionId, id);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "无法发布用例清单：" + e.getMessage());
        }
    }

    public void clearCurrent(Kind kind, long questionId) {
        manifests.clearCurrent(kind.name(), questionId);
    }

    private JudgeManifest codeManifest(long questionId, Map<String, Artifact> refs)
            throws IOException {
        var question = questions.findByIdForUpdate(questionId);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        List<CaseSpec> items = new ArrayList<>();
        for (var tc : cases.findForManifest(questionId)) {
            var input =
                    caseArtifact(
                            questionId,
                            tc.getId(),
                            "input",
                            tc.getInputPath(),
                            tc.getInput(),
                            refs);
            var output =
                    caseArtifact(
                            questionId,
                            tc.getId(),
                            "output",
                            tc.getOutputPath(),
                            tc.getExpectedOutput(),
                            refs);
            items.add(
                    new CaseSpec(
                            tc.getId(),
                            tc.getCaseNum() != null ? tc.getCaseNum() : 1,
                            tc.getScoreWeight() != null ? tc.getScoreWeight() : 10,
                            Integer.valueOf(1).equals(tc.getIsSample()),
                            input,
                            output,
                            null));
        }
        return new JudgeManifest(
                1,
                Kind.ALGO.name(),
                question.getTimeLimit() != null ? question.getTimeLimit() : 1000,
                question.getMemoryLimit() != null ? question.getMemoryLimit() : 256,
                null,
                items);
    }

    private JudgeManifest sqlManifest(long questionId, Map<String, Artifact> refs)
            throws IOException {
        var question = dbQuestions.findByIdForUpdate(questionId);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        var sql =
                new SqlSpec(
                        question.getDialect(),
                        question.getType(),
                        text(question.getSchemaSql(), refs),
                        text(question.getReferenceSql(), refs),
                        text(question.getVerifySql(), refs),
                        Integer.valueOf(1).equals(question.getOrderSensitive()));
        List<CaseSpec> items = new ArrayList<>();
        for (var tc : dbCases.findForManifest(questionId)) {
            items.add(
                    new CaseSpec(
                            tc.getId(),
                            tc.getCaseOrder(),
                            0,
                            Integer.valueOf(1).equals(tc.getIsSample()),
                            null,
                            text(tc.getExpectedOutput(), refs),
                            text(tc.getSeedSql(), refs)));
        }
        return new JudgeManifest(1, Kind.DATABASE.name(), 5000, null, sql, items);
    }

    private Artifact caseArtifact(
            long questionId,
            long caseId,
            String name,
            String relativePath,
            String inline,
            Map<String, Artifact> refs)
            throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            if (inline == null && "output".equals(name)) {
                throw new IOException("用例 " + caseId + " 缺少期望输出");
            }
            return text(inline != null ? inline : "", refs);
        }
        try {
            var file = legacyFiles.resolvePath(relativePath);
            var questionDirectory = legacyFiles.getQuestionDir(questionId);
            var allowedDirectory =
                    questionDirectory.getParent().toRealPath().resolve(Long.toString(questionId));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(allowedDirectory)) {
                throw new IOException("Invalid case file");
            }
            try (var source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var ref = artifacts.store(source);
                refs.put(ref.digest(), ref);
                return ref;
            }
        } catch (IOException e) {
            throw new IOException("用例 " + caseId + " 的 " + name + " 文件不可用", e);
        }
    }

    private Artifact text(String value, Map<String, Artifact> refs) throws IOException {
        if (value == null) {
            return null;
        }
        try (var source = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))) {
            var ref = artifacts.store(source);
            refs.put(ref.digest(), ref);
            return ref;
        }
    }
}
