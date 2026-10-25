package com.kece.system.dto;

import java.io.Serializable;

/**
 * 首页技术演示 Java 算法代码片段。
 * 来源于 GitHub: https://github.com/zheco68-collab/Templat_summary
 */
public class DemoSnippetVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 模板代码文件名，如 "快速幂.java" 或 "二叉树深度.java" */
    private String filename;

    /** 语言与运行版本徽标，固定为 "Java 17" */
    private String langBadge;

    /** Java 源代码内容 */
    private String code;

    /** 在仓库中的相对路径，如 "算法/数学/快速幂.java" */
    private String sourcePath;

    /** 最近一次刷新时间戳 (毫秒) */
    private long updatedAt;

    public DemoSnippetVO() {
    }

    public DemoSnippetVO(String filename, String langBadge, String code, String sourcePath, long updatedAt) {
        this.filename = filename;
        this.langBadge = langBadge;
        this.code = code;
        this.sourcePath = sourcePath;
        this.updatedAt = updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String filename;
        private String langBadge;
        private String code;
        private String sourcePath;
        private long updatedAt;

        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder langBadge(String langBadge) {
            this.langBadge = langBadge;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public Builder updatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public DemoSnippetVO build() {
            return new DemoSnippetVO(filename, langBadge, code, sourcePath, updatedAt);
        }
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getLangBadge() {
        return langBadge;
    }

    public void setLangBadge(String langBadge) {
        this.langBadge = langBadge;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
