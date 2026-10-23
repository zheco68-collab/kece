package com.kece.question.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.question.entity.TestCase;
import com.kece.question.mapper.TestCaseMapper;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 编程题测试用例文件存储服务。
 * 负责用例落盘、历史数据平滑迁移、大文件流式读写与 Zip 压缩包批量导入导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseStorageService {

    private final TestCaseMapper testCaseMapper;

    @Value("${kece.testcase.dir:testcases}")
    private String testcaseDir;

    private Path rootPath;

    public static final long MAX_PREVIEW_BYTES = 64 * 1024; // 64KB 以上视为大文件预览截断

    @PostConstruct
    public void init() {
        this.rootPath = Paths.get(testcaseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootPath);
            log.info("Initialized testcase storage directory at: {}", this.rootPath);
            // 启动时自动执行老数据平滑迁移
            migrateLegacyCases();
        } catch (IOException e) {
            log.error("Failed to initialize testcase storage dir: {}", this.rootPath, e);
        }
    }

    /** 获取题目用例所在根目录绝对路径 */
    public Path getQuestionDir(Long questionId) {
        Path dir = rootPath.resolve(String.valueOf(questionId)).normalize();
        if (!dir.startsWith(rootPath)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "非法的题目路径");
        }
        return dir;
    }

    /** 检查相对路径是否越界 */
    public Path resolvePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path path = rootPath.resolve(relativePath).normalize();
        if (!path.startsWith(rootPath)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "非法的测试文件路径");
        }
        return path;
    }

    /**
     * 单用例写入文件
     */
    public void writeCaseFiles(TestCase tc, String input, String expectedOutput) {
        Long qId = tc.getQuestionId();
        Path qDir = getQuestionDir(qId);
        try {
            Files.createDirectories(qDir);
            String baseName = tc.getCaseNum() != null && tc.getCaseNum() > 0
                    ? String.valueOf(tc.getCaseNum())
                    : (tc.getId() != null ? String.valueOf(tc.getId()) : ("tmp_" + System.currentTimeMillis()));

            Path inPath = qDir.resolve(baseName + ".in");
            Path outPath = qDir.resolve(baseName + ".out");

            byte[] inBytes = input != null ? input.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] outBytes = expectedOutput != null ? expectedOutput.getBytes(StandardCharsets.UTF_8) : new byte[0];

            Files.write(inPath, inBytes);
            Files.write(outPath, outBytes);

            tc.setInputPath(qId + "/" + inPath.getFileName().toString());
            tc.setOutputPath(qId + "/" + outPath.getFileName().toString());
            tc.setInputSize((long) inBytes.length);
            tc.setOutputSize((long) outBytes.length);
        } catch (IOException e) {
            log.error("Failed to write testcase files for question {}, case {}", qId, tc.getId(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "测试用例文件写入失败: " + e.getMessage());
        }
    }

    /**
     * 读取输入文本（小于 MAX_PREVIEW_BYTES 完整读取，大于则截断返回）
     */
    public String readInputText(TestCase tc) {
        if (tc.getInputPath() != null) {
            Path path = resolvePath(tc.getInputPath());
            if (path != null && Files.exists(path)) {
                return readSafeText(path, tc.getInputSize());
            }
        }
        return tc.getInput() != null ? tc.getInput() : "";
    }

    /**
     * 读取期望输出文本（小于 MAX_PREVIEW_BYTES 完整读取，大于则截断返回）
     */
    public String readOutputText(TestCase tc) {
        if (tc.getOutputPath() != null) {
            Path path = resolvePath(tc.getOutputPath());
            if (path != null && Files.exists(path)) {
                return readSafeText(path, tc.getOutputSize());
            }
        }
        return tc.getExpectedOutput() != null ? tc.getExpectedOutput() : "";
    }

    private String readSafeText(Path path, Long size) {
        try {
            long fileSize = size != null ? size : Files.size(path);
            if (fileSize > MAX_PREVIEW_BYTES) {
                // 读取前 16KB 作为预览
                byte[] preview = new byte[16 * 1024];
                try (InputStream in = Files.newInputStream(path)) {
                    int read = in.read(preview);
                    return new String(preview, 0, Math.max(read, 0), StandardCharsets.UTF_8)
                            + "\n... [大文件已截断，总大小: " + (fileSize / 1024) + " KB]";
                }
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read text from {}", path, e);
            return "";
        }
    }

    public Resource getInputResource(TestCase tc) {
        if (tc.getInputPath() == null) return null;
        Path p = resolvePath(tc.getInputPath());
        return p != null && Files.exists(p) ? new FileSystemResource(p) : null;
    }

    public Resource getOutputResource(TestCase tc) {
        if (tc.getOutputPath() == null) return null;
        Path p = resolvePath(tc.getOutputPath());
        return p != null && Files.exists(p) ? new FileSystemResource(p) : null;
    }

    /**
     * 自动平滑迁移未文件化的老用例
     */
    @Transactional
    public void migrateLegacyCases() {
        List<TestCase> unmigrated = testCaseMapper.findUnmigrated();
        if (unmigrated == null || unmigrated.isEmpty()) {
            return;
        }
        log.info("Starting migration of {} legacy test cases to file storage...", unmigrated.size());
        Map<Long, Integer> questionSeq = new HashMap<>();

        int count = 0;
        for (TestCase tc : unmigrated) {
            Long qId = tc.getQuestionId();
            int seq = questionSeq.getOrDefault(qId, 1);
            questionSeq.put(qId, seq + 1);

            tc.setCaseNum(seq);
            if (tc.getScoreWeight() == null || tc.getScoreWeight() <= 0) {
                tc.setScoreWeight(10);
            }

            writeCaseFiles(tc, tc.getInput(), tc.getExpectedOutput());

            // 非公开样例且内容已安全落盘，清空数据库文本以彻底卸载 MySQL 存储与传输负担
            if (Objects.equals(tc.getIsSample(), 0)) {
                tc.setInput(null);
                tc.setExpectedOutput(null);
            }
            testCaseMapper.update(tc);
            count++;
        }
        log.info("Successfully migrated {} legacy test cases to file storage!", count);
    }

    /**
     * Zip 压缩包批量导入测试用例
     */
    @Transactional
    public List<TestCase> importZip(Long questionId, MultipartFile file, boolean replaceExisting, boolean firstAsSample) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的 Zip 压缩包");
        }

        Path qDir = getQuestionDir(questionId);
        try {
            Files.createDirectories(qDir);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "无法创建题目文件目录");
        }

        // 读取 Zip 中所有文件条目
        Map<String, byte[]> fileMap = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 忽略系统生成的隐藏文件（如 __MACOSX 等）
                if (name.contains("__MACOSX") || name.startsWith(".") || name.contains("/.")) {
                    continue;
                }
                // 只取文件名本身，忽略子目录路径
                String fileName = Paths.get(name).getFileName().toString();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                fileMap.put(fileName, baos.toByteArray());
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Zip 解压失败，请确保格式正确: " + e.getMessage());
        }

        if (fileMap.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "压缩包内未包含有效文件");
        }

        // 配对规则：
        // 支持后缀：.in 与 .out / .ans
        // 或 input*.txt 与 output*.txt / ans*.txt
        List<CasePair> pairs = matchPairs(fileMap);
        if (pairs.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "未能识别到成对的输入输出文件。命名建议: 1.in/1.out、input1.txt/output1.txt 等");
        }

        // 按文件名天然自然排序 (1, 2, 3... 10)
        pairs.sort(Comparator.comparing(CasePair::getSortKey, new NaturalOrderComparator()));

        if (replaceExisting) {
            // 清理已有文件和记录
            try {
                if (Files.exists(qDir)) {
                    try (var s = Files.list(qDir)) {
                        s.forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception ignored) {}
            testCaseMapper.deleteByQuestionId(questionId);
        }

        List<TestCase> existing = testCaseMapper.findByQuestionId(questionId);
        int startSeq = existing.size() + 1;

        List<TestCase> newCases = new ArrayList<>();
        int currentSeq = startSeq;

        for (int i = 0; i < pairs.size(); i++) {
            CasePair pair = pairs.get(i);
            int caseNum = currentSeq++;
            String inFileName = caseNum + ".in";
            String outFileName = caseNum + ".out";

            Path targetIn = qDir.resolve(inFileName);
            Path targetOut = qDir.resolve(outFileName);

            try {
                Files.write(targetIn, pair.getInBytes());
                Files.write(targetOut, pair.getOutBytes());
            } catch (IOException e) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "用例文件保存失败: " + e.getMessage());
            }

            TestCase tc = new TestCase();
            tc.setQuestionId(questionId);
            tc.setCaseNum(caseNum);
            tc.setInputPath(questionId + "/" + inFileName);
            tc.setOutputPath(questionId + "/" + outFileName);
            tc.setInputSize((long) pair.getInBytes().length);
            tc.setOutputSize((long) pair.getOutBytes().length);
            tc.setScoreWeight(10);
            
            // 是否判定为公开样例：第一条且启用勾选，或者文件名中包含 sample
            boolean isSample = (firstAsSample && i == 0 && startSeq == 1) || pair.getBaseName().toLowerCase().contains("sample");
            tc.setIsSample(isSample ? 1 : 0);

            // 仅公开样例（前端题目详情 Markdown 预览需要）在 DB 中保留一份文本副本；其余非公开测试点纯文件系统托管，DB 中不存文本，彻底卸载 MySQL 性能负担
            if (isSample) {
                tc.setInput(new String(pair.getInBytes(), StandardCharsets.UTF_8));
                tc.setExpectedOutput(new String(pair.getOutBytes(), StandardCharsets.UTF_8));
            } else {
                tc.setInput(null);
                tc.setExpectedOutput(null);
            }

            testCaseMapper.insert(tc);
            newCases.add(tc);
        }

        return newCases;
    }

    /**
     * 导出该题所有测试用例为 Zip 压缩包流（流式传输，避免将大文件读取为 byte 数组占用堆内存）
     */
    public void exportZip(Long questionId, ZipOutputStream zos) throws IOException {
        List<TestCase> cases = testCaseMapper.findByQuestionId(questionId);
        if (cases == null || cases.isEmpty()) {
            return;
        }

        for (TestCase tc : cases) {
            int num = tc.getCaseNum() != null ? tc.getCaseNum() : tc.getId().intValue();
            String inName = num + ".in";
            String outName = num + ".out";

            // 写入 in
            zos.putNextEntry(new ZipEntry(inName));
            if (tc.getInputPath() != null) {
                Path p = resolvePath(tc.getInputPath());
                if (p != null && Files.exists(p)) {
                    try (InputStream in = Files.newInputStream(p)) {
                        in.transferTo(zos);
                    }
                } else if (tc.getInput() != null) {
                    zos.write(tc.getInput().getBytes(StandardCharsets.UTF_8));
                }
            } else if (tc.getInput() != null) {
                zos.write(tc.getInput().getBytes(StandardCharsets.UTF_8));
            }
            zos.closeEntry();

            // 写入 out
            zos.putNextEntry(new ZipEntry(outName));
            if (tc.getOutputPath() != null) {
                Path p = resolvePath(tc.getOutputPath());
                if (p != null && Files.exists(p)) {
                    try (InputStream in = Files.newInputStream(p)) {
                        in.transferTo(zos);
                    }
                } else if (tc.getExpectedOutput() != null) {
                    zos.write(tc.getExpectedOutput().getBytes(StandardCharsets.UTF_8));
                }
            } else if (tc.getExpectedOutput() != null) {
                zos.write(tc.getExpectedOutput().getBytes(StandardCharsets.UTF_8));
            }
            zos.closeEntry();
        }
    }

    /** 配对辅助类 */
    @Getter
    public static class CasePair {
        private final String baseName;
        private final String sortKey;
        private final byte[] inBytes;
        private final byte[] outBytes;

        public CasePair(String baseName, String sortKey, byte[] inBytes, byte[] outBytes) {
            this.baseName = baseName;
            this.sortKey = sortKey;
            this.inBytes = inBytes;
            this.outBytes = outBytes;
        }
    }

    private List<CasePair> matchPairs(Map<String, byte[]> fileMap) {
        List<CasePair> pairs = new ArrayList<>();
        Map<String, byte[]> inMap = new HashMap<>();
        Map<String, byte[]> outMap = new HashMap<>();

        Pattern inPattern = Pattern.compile("^(.*?)(?:[._-]?in|[._-]?input)?\\.?(?:in|txt|data)?$", Pattern.CASE_INSENSITIVE);

        for (Map.Entry<String, byte[]> e : fileMap.entrySet()) {
            String name = e.getKey();
            String lower = name.toLowerCase();

            if (lower.endsWith(".in")) {
                String base = name.substring(0, name.length() - 3);
                inMap.put(base, e.getValue());
            } else if (lower.endsWith(".out") || lower.endsWith(".ans")) {
                String base = name.substring(0, name.lastIndexOf('.'));
                outMap.put(base, e.getValue());
            } else if (lower.startsWith("input") || lower.contains(".in.")) {
                String num = name.replaceAll("[^0-9a-zA-Z]", "");
                inMap.put(num, e.getValue());
            } else if (lower.startsWith("output") || lower.startsWith("ans")) {
                String num = name.replaceAll("[^0-9a-zA-Z]", "");
                outMap.put(num, e.getValue());
            }
        }

        // 精确 baseName 配对
        for (String base : inMap.keySet()) {
            if (outMap.containsKey(base)) {
                pairs.add(new CasePair(base, base, inMap.get(base), outMap.get(base)));
            }
        }

        // 如果上述常规后缀没配出，按常见数字前缀配对 (如 1.in 和 1.out)
        if (pairs.isEmpty()) {
            for (String inName : fileMap.keySet()) {
                if (inName.toLowerCase().endsWith(".in") || inName.toLowerCase().contains("in")) {
                    String base = inName.replaceAll("[._-]?(in|input).*", "");
                    for (String outName : fileMap.keySet()) {
                        if (outName.toLowerCase().endsWith(".out") || outName.toLowerCase().endsWith(".ans")) {
                            String outBase = outName.replaceAll("[._-]?(out|ans|output).*", "");
                            if (base.equalsIgnoreCase(outBase)) {
                                pairs.add(new CasePair(base, base, fileMap.get(inName), fileMap.get(outName)));
                                break;
                            }
                        }
                    }
                }
            }
        }

        return pairs;
    }

    /** 自然排序比较器（支持 1, 2, ... 10 正确排序） */
    private static class NaturalOrderComparator implements Comparator<String> {
        private static final Pattern NUM_PATTERN = Pattern.compile("(\\d+)|(\\D+)");

        @Override
        public int compare(String s1, String s2) {
            Matcher m1 = NUM_PATTERN.matcher(s1);
            Matcher m2 = NUM_PATTERN.matcher(s2);

            while (m1.find() && m2.find()) {
                String g1 = m1.group();
                String g2 = m2.group();

                if (Character.isDigit(g1.charAt(0)) && Character.isDigit(g2.charAt(0))) {
                    try {
                        long n1 = Long.parseLong(g1);
                        long n2 = Long.parseLong(g2);
                        if (n1 != n2) {
                            return Long.compare(n1, n2);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                int cmp = g1.compareToIgnoreCase(g2);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(s1.length(), s2.length());
        }
    }
}
