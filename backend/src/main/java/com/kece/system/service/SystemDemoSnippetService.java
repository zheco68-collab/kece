package com.kece.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.system.dto.DemoSnippetVO;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 首页技术演示 Java 算法代码片段获取与高可用容灾服务。
 * <p>
 * 数据来源：https://github.com/zheco68-collab/Templat_summary (纯 Java 算法/数据结构模板库)
 * 刷新周期：1) 系统启动完成时异步加载； 2) 每天北京时间 00:00:00 (Asia/Shanghai) 自动拉取。
 * <p>
 * 高可靠与低延迟保障机制：
 * 1. 读写彻底解耦：前端请求直接读取内存变量 (耗时 < 1ms)，永远不阻塞在外部网络；
 * 2. 严格 1500ms 超时熔断：外部 HTTP 交互全流程限时，超时立即中断；
 * 3. 本地磁盘持久化快照：拉取成功自动落盘 demo_java_snippet.json，断网重启秒级从快照恢复；
 * 4. 内置经典种子保底：全新环境无网络启动时兜底输出经典 Java 17 二分算法，绝不抛错；
 * 5. 国内高速通道镜像容灾：支持 ghproxy/gitmirror 加速，避开国内直连 raw.githubusercontent.com 的 DNS 污染。
 */
@Service
public class SystemDemoSnippetService {

    private static final Logger log = LoggerFactory.getLogger(SystemDemoSnippetService.class);

    private static final String GITHUB_REPO_TREE_URL =
            "https://api.github.com/repos/zheco68-collab/Templat_summary/git/trees/main?recursive=1";

    /** 多通道 Raw 下载源（首选国内高可用加速通道，备选官方源） */
    private static final String[] RAW_MIRROR_BASES = new String[] {
            "https://ghproxy.net/https://raw.githubusercontent.com/zheco68-collab/Templat_summary/main/",
            "https://raw.gitmirror.com/zheco68-collab/Templat_summary/main/",
            "https://raw.githubusercontent.com/zheco68-collab/Templat_summary/main/"
    };

    /** 硬编码保底种子代码（Java 17 经典算法，零依赖） */
    private static final String DEFAULT_SEED_CODE = """
            // ==========================================
            // 算法模板 · 二分查找与边界判定 (Java 17)
            // 评测环境: Linux Cgroups v2 沙箱 · 严格受控
            // ==========================================
            import java.util.*;

            public class Solution {
                /**
                 * 在有序数组中查找目标值的下标，未找到返回 -1
                 * 时间复杂度: O(log N)，空间复杂度: O(1)
                 */
                public int search(int[] nums, int target) {
                    if (nums == null || nums.length == 0) {
                        return -1;
                    }
                    int left = 0, right = nums.length - 1;
                    while (left <= right) {
                        int mid = left + (right - left) / 2;
                        if (nums[mid] == target) {
                            return mid;
                        } else if (nums[mid] < target) {
                            left = mid + 1;
                        } else {
                            right = mid - 1;
                        }
                    }
                    return -1;
                }
            }
            """;

    /** 预置文件路径种子（当 GitHub Tree API 偶发超时或被限流时备用） */
    private static final List<String> SEED_PATHS = List.of(
            "数据结构/二叉树/二叉树深度.java",
            "数据结构/二叉树/二叉树最大宽度.java",
            "数据结构/二叉树/二叉树的层序遍历.java",
            "数据结构/二叉树/AVL树模板.java",
            "算法/数学/快速幂.java",
            "算法/双指针/滑动窗口_元素翻转_替换模板.java",
            "算法/图论/最短路/dijkstra.java"
    );

    private final ObjectMapper objectMapper;

    @Value("${kece.demo.snippet-cache-dir:data/cache}")
    private String cacheDir = "data/cache";

    private static final String CACHE_FILE_NAME = "demo_java_snippet.json";
    private static final String CACHE_TREE_FILE_NAME = "demo_repo_tree.json";

    /** 线程安全的内存高可用常驻变量 */
    private final AtomicReference<DemoSnippetVO> currentSnippet = new AtomicReference<>();

    public SystemDemoSnippetService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 系统启动初始化：
     * 1. 立即加载默认种子，保证接口 0ms 启动可用；
     * 2. 尝试从本地磁盘持久化快照恢复；
     * 3. 异步尝试联网抓取 GitHub 最新代码（不阻塞 Spring 容器启动主线程）。
     */
    @PostConstruct
    public void init() {
        // 1. 初始化内置种子
        DemoSnippetVO initialVO = DemoSnippetVO.builder()
                .filename("Solution.java")
                .langBadge("Java 17")
                .code(DEFAULT_SEED_CODE.trim())
                .sourcePath("内置经典模板")
                .updatedAt(System.currentTimeMillis())
                .build();
        currentSnippet.set(initialVO);

        // 2. 尝试从本地磁盘快照恢复
        loadSnapshotFromDisk();

        // 3. 异步尝试联网抓取 GitHub 最新代码
        CompletableFuture.runAsync(this::refreshSnippetFromGitHub);
    }

    /**
     * 每天北京时间晚上 12 点整 (00:00:00) 自动触发拉取。
     * 必须指定 zone = "Asia/Shanghai"，防止宿主机因 UTC 时区产生 8 小时偏差。
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Shanghai")
    public void scheduledMidnightRefresh() {
        log.info("Triggered scheduled midnight refresh for Java demo snippet (Asia/Shanghai 00:00:00)...");
        CompletableFuture.runAsync(this::refreshSnippetFromGitHub);
    }

    /**
     * 对外提供极速内存读取（耗时 < 1ms），任何未登录前台用户均可无感知秒级获取。
     */
    public DemoSnippetVO getCurrentSnippet() {
        DemoSnippetVO vo = currentSnippet.get();
        if (vo == null) {
            return DemoSnippetVO.builder()
                    .filename("Solution.java")
                    .langBadge("Java 17")
                    .code(DEFAULT_SEED_CODE.trim())
                    .sourcePath("内置经典模板")
                    .updatedAt(System.currentTimeMillis())
                    .build();
        }
        return vo;
    }

    /**
     * 从 GitHub 异步抓取随机 Java 算法模板代码。
     * 全流程严格执行 1500ms 超时限制与多级降级。
     */
    public synchronized void refreshSnippetFromGitHub() {
        long startTime = System.currentTimeMillis();
        log.info("Starting to fetch random Java algorithm snippet from GitHub: Templat_summary...");

        // 构建原生轻量 HttpClient，设置 1000ms 握手超时
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            // 步骤 1：获取仓库文件树列表（单步限制 1500ms，失败自动回退本地树快照或 SEED_PATHS）
            List<FileNode> javaFiles = fetchJavaFileList(client);
            if (javaFiles.isEmpty()) {
                javaFiles = loadTreeFromDisk();
            }
            if (javaFiles.isEmpty()) {
                log.warn("No Java files found from GitHub API or disk, using fallback path seeds.");
                for (String p : SEED_PATHS) {
                    javaFiles.add(new FileNode(p, null));
                }
            }

            // 步骤 2：随机抽取一个 Java 文件
            int randomIdx = ThreadLocalRandom.current().nextInt(javaFiles.size());
            FileNode chosenNode = javaFiles.get(randomIdx);
            log.info("Selected random Java snippet target: {}", chosenNode.path);

            // 步骤 3：获取文件代码内容（外网独立限时 1500ms，优先通过国内高速镜像）
            String javaCode = fetchFileContent(client, chosenNode, 1500);
            if (javaCode == null || javaCode.trim().isEmpty()) {
                throw new IOException("Fetched Java code is empty for: " + chosenNode.path);
            }

            // 提取简要文件名，例如 "数据结构/二叉树/二叉树深度.java" -> "二叉树深度.java"
            String simpleFilename = chosenNode.path.contains("/")
                    ? chosenNode.path.substring(chosenNode.path.lastIndexOf('/') + 1)
                    : chosenNode.path;
            if (!simpleFilename.endsWith(".java")) {
                simpleFilename += ".java";
            }

            DemoSnippetVO freshVO = DemoSnippetVO.builder()
                    .filename(simpleFilename)
                    .langBadge("Java 17")
                    .code(javaCode.trim())
                    .sourcePath(chosenNode.path)
                    .updatedAt(System.currentTimeMillis())
                    .build();

            // 步骤 4：原子替换内存常驻变量
            currentSnippet.set(freshVO);
            log.info("Successfully updated memory Java demo snippet: {} (length: {} chars, took: {} ms)",
                    freshVO.getFilename(), freshVO.getCode().length(), System.currentTimeMillis() - startTime);

            // 步骤 5：持久化快照到本地磁盘
            saveSnapshotToDisk(freshVO);

        } catch (HttpTimeoutException e) {
            log.warn("GitHub demo snippet request timed out (>1500ms): {}. Gracefully keeping existing snippet.", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to fetch demo snippet from GitHub: {}. Gracefully keeping existing snippet.", e.getMessage());
        }
    }

    /**
     * 获取仓库全部 .java 文件列表
     */
    private List<FileNode> fetchJavaFileList(HttpClient client) {
        List<FileNode> result = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_REPO_TREE_URL))
                    .timeout(Duration.ofMillis(1500))
                    .header("User-Agent", "KECE-Backend-SnippetFetcher/1.0")
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode treeNode = root.get("tree");
                if (treeNode != null && treeNode.isArray()) {
                    for (JsonNode item : treeNode) {
                        String path = item.has("path") ? item.get("path").asText() : "";
                        String type = item.has("type") ? item.get("type").asText() : "";
                        if ("blob".equalsIgnoreCase(type) && path.endsWith(".java")) {
                            String blobUrl = item.has("url") ? item.get("url").asText() : null;
                            result.add(new FileNode(path, blobUrl));
                        }
                    }
                    if (!result.isEmpty()) {
                        saveTreeToDisk(result);
                    }
                }
            } else {
                log.warn("GitHub tree API returned non-200 status: {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Exception querying GitHub tree API: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 获取单个 Java 文件的源码内容（优先通过国内高速镜像与 GitHub Blob API）
     */
    private String fetchFileContent(HttpClient client, FileNode node, long timeoutMs) {
        // 通道 1：依次尝试 Raw 镜像与官方源
        String[] segments = node.path.split("/");
        StringBuilder encodedPath = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) encodedPath.append("/");
            encodedPath.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }

        for (String base : RAW_MIRROR_BASES) {
            try {
                String rawUrl = base + encodedPath;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(rawUrl))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("User-Agent", "KECE-Backend-SnippetFetcher/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200 && response.body() != null && response.body().length() > 20) {
                    return response.body();
                }
            } catch (Exception e) {
                log.debug("Mirror {} failed for {}: {}", base, node.path, e.getMessage());
            }
        }

        // 通道 2：通过 GitHub Blob API Base64 解析
        if (node.blobUrl != null && !node.blobUrl.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(node.blobUrl))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("User-Agent", "KECE-Backend-SnippetFetcher/1.0")
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    if (root.has("content")) {
                        String base64Content = root.get("content").asText().replaceAll("\\s+", "");
                        byte[] decoded = Base64.getDecoder().decode(base64Content);
                        return new String(decoded, StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch via GitHub Blob API: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * 从本地磁盘快照恢复
     */
    private void loadSnapshotFromDisk() {
        try {
            Path filePath = Paths.get(cacheDir, CACHE_FILE_NAME).toAbsolutePath().normalize();
            if (Files.exists(filePath)) {
                String json = Files.readString(filePath, StandardCharsets.UTF_8);
                DemoSnippetVO cachedVO = objectMapper.readValue(json, DemoSnippetVO.class);
                if (cachedVO != null && cachedVO.getCode() != null && !cachedVO.getCode().isBlank()) {
                    currentSnippet.set(cachedVO);
                    log.info("Successfully restored Java demo snippet from local disk snapshot: {}", cachedVO.getFilename());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read demo snippet disk snapshot: {}", e.getMessage());
        }
    }

    /**
     * 将最新抓取成功的代码持久化快照到本地磁盘
     */
    private void saveSnapshotToDisk(DemoSnippetVO vo) {
        try {
            Path dirPath = Paths.get(cacheDir).toAbsolutePath().normalize();
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(CACHE_FILE_NAME);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(vo);
            Files.writeString(filePath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Persisted demo snippet snapshot to disk at: {}", filePath);
        } catch (Exception e) {
            log.warn("Failed to persist demo snippet snapshot to disk: {}", e.getMessage());
        }
    }

    private void saveTreeToDisk(List<FileNode> tree) {
        try {
            Path dirPath = Paths.get(cacheDir).toAbsolutePath().normalize();
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(CACHE_TREE_FILE_NAME);
            String json = objectMapper.writeValueAsString(tree);
            Files.writeString(filePath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private List<FileNode> loadTreeFromDisk() {
        List<FileNode> list = new ArrayList<>();
        try {
            Path filePath = Paths.get(cacheDir, CACHE_TREE_FILE_NAME).toAbsolutePath().normalize();
            if (Files.exists(filePath)) {
                JsonNode root = objectMapper.readTree(Files.readString(filePath, StandardCharsets.UTF_8));
                if (root != null && root.isArray()) {
                    for (JsonNode item : root) {
                        String p = item.has("path") ? item.get("path").asText() : "";
                        String u = item.has("blobUrl") ? item.get("blobUrl").asText() : null;
                        if (!p.isBlank()) list.add(new FileNode(p, u));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static class FileNode {
        public String path;
        public String blobUrl;

        public FileNode() {}

        public FileNode(String path, String blobUrl) {
            this.path = path;
            this.blobUrl = blobUrl;
        }
    }
}
