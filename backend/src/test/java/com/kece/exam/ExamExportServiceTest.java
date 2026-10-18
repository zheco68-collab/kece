package com.kece.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kece.exam.dto.ExamStudentInfoVO;
import com.kece.exam.dto.ExamStudentPaperQuestionVO;
import com.kece.exam.dto.ExamStudentPaperVO;
import com.kece.exam.dto.ExamVO;
import com.kece.exam.service.ExamAdminService;
import com.kece.exam.service.ExamExportService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamExportServiceTest {

    @Test
    void testExportPrintableHtml_withSeparatedQuestionTypes() {
        ExamAdminService examAdminService = mock(ExamAdminService.class);
        ExamExportService exportService = new ExamExportService(examAdminService, new com.fasterxml.jackson.databind.ObjectMapper());

        ExamVO exam = new ExamVO();
        exam.setId(1L);
        exam.setTitle("专业综合实训");
        exam.setMode("OI");
        exam.setSchoolName("湖北科技职业学院");
        exam.setSemester("2025-2026学年度第一学期期末考试");

        ExamStudentInfoVO student = new ExamStudentInfoVO();
        student.setRealName("张三");
        student.setStudentNo("2023001");
        student.setCollege("软件工程学院");
        student.setMajor("软件技术");
        student.setClassName("2301班");

        ExamStudentPaperQuestionVO single = new ExamStudentPaperQuestionVO();
        single.setQuestionType("OBJECTIVE");
        single.setObjectiveType("SINGLE");
        single.setTitle("单选题1");
        single.setFullScore(2);
        single.setStudentScore(2);
        single.setIsCorrect(true);

        ExamStudentPaperQuestionVO judge = new ExamStudentPaperQuestionVO();
        judge.setQuestionType("OBJECTIVE");
        judge.setObjectiveType("JUDGE");
        judge.setTitle("判断题1");
        judge.setFullScore(2);
        judge.setStudentScore(2);
        judge.setIsCorrect(true);

        ExamStudentPaperQuestionVO multi = new ExamStudentPaperQuestionVO();
        multi.setQuestionType("OBJECTIVE");
        multi.setObjectiveType("MULTIPLE");
        multi.setTitle("多选题1");
        multi.setFullScore(3);
        multi.setStudentScore(3);
        multi.setIsCorrect(true);

        ExamStudentPaperQuestionVO fill = new ExamStudentPaperQuestionVO();
        fill.setQuestionType("OBJECTIVE");
        fill.setObjectiveType("FILL");
        fill.setTitle("填空题1");
        fill.setFullScore(2);
        fill.setStudentScore(2);
        fill.setIsCorrect(true);

        ExamStudentPaperQuestionVO db = new ExamStudentPaperQuestionVO();
        db.setQuestionType("DB");
        db.setTitle("SQL查询题1");
        db.setFullScore(10);
        db.setStudentScore(10);
        db.setIsCorrect(true);

        ExamStudentPaperQuestionVO algo = new ExamStudentPaperQuestionVO();
        algo.setQuestionType("ALGO");
        algo.setTitle("算法编程题1");
        algo.setFullScore(20);
        algo.setStudentScore(20);
        algo.setIsCorrect(true);

        ExamStudentPaperVO paper = new ExamStudentPaperVO();
        paper.setExam(exam);
        paper.setStudentInfo(student);
        paper.setUserId(100L);
        paper.setUsername("zhangsan");
        paper.setFullScore(39);
        paper.setTotalScore(39);
        paper.setQuestions(List.of(single, judge, multi, fill, db, algo));

        when(examAdminService.getStudentPaper(1L, 100L)).thenReturn(paper);

        String html = exportService.getPrintableHtml(1L, 100L);

        // 验证大题型分组与考生试卷页面完全一致
        assertThat(html).contains("一、选择题");
        assertThat(html).contains("二、判断题");
        assertThat(html).contains("三、填空题");
        assertThat(html).contains("四、数据库 SQL 实操题");
        assertThat(html).contains("五、编程算法实操题");

        // 验证学生信息
        assertThat(html).contains("张三");
        assertThat(html).contains("2023001");
        assertThat(html).contains("2301班");
        assertThat(html).contains("密封线内禁止答题");
        assertThat(html).contains("卷面满分");
        assertThat(html).contains("考生实得");
        assertThat(html).contains("评卷人");
        assertThat(html).contains("试卷评分标准与分值配比汇总");
    }

    @Test
    void testCleanAndFormatMarkdown() {
        ExamExportService exportService = new ExamExportService(null, new com.fasterxml.jackson.databind.ObjectMapper());
        String md = "## 题目描述\n\n编写一条 UPDATE 语句，将**研发部（dept = 'RD'）**所有员工的薪资上调 10%。\n\n## 输入格式\n一行，两个整数 `a` 和 `b`（ $-10^9 \\le a, b \\le 10^9$ ）。";
        String html = exportService.cleanAndFormatMarkdown(md);

        assertThat(html).contains("【题目描述】");
        assertThat(html).contains("【输入格式】");
        assertThat(html).contains("<b>研发部（dept = &#39;RD&#39;）</b>");
        assertThat(html).contains("<code class=\"inline-code\">a</code>");
        assertThat(html).contains("≤");
        assertThat(html).doesNotContain("##");
        assertThat(html).doesNotContain("\\le");
    }

    @Test
    void testFormatStudentCodeAnswer_fromJsonToFile() {
        ExamExportService exportService = new ExamExportService(null, new com.fasterxml.jackson.databind.ObjectMapper());
        String json = "{\"Main.java\":\"import java.util.*;\\n\\npublic class Main {\\n    public static void main(String[] args) {\\n        Scanner sc = new Scanner(System.in);\\n    }\\n}\"}";
        String html = exportService.formatStudentAnswerHtml(json, "ALGO");

        assertThat(html).contains("// 文件: Main.java");
        assertThat(html).contains("public class Main");
        assertThat(html).contains("Scanner sc = new Scanner(System.in);");
        assertThat(html).doesNotContain("{\"Main.java\"");
    }

    @Test
    void testExportBatchZip() throws Exception {
        ExamAdminService examAdminService = mock(ExamAdminService.class);
        ExamExportService exportService = new ExamExportService(examAdminService, new com.fasterxml.jackson.databind.ObjectMapper());

        ExamVO exam = new ExamVO();
        exam.setId(1L);
        exam.setTitle("专业综合实训");
        exam.setMode("OI");
        exam.setSchoolName("湖北科技职业学院");
        exam.setSemester("2025-2026学年度第一学期期末考试");

        com.kece.exam.dto.ExamStudentSubmissionRowVO row = com.kece.exam.dto.ExamStudentSubmissionRowVO.builder()
                .userId(100L)
                .username("zhangsan")
                .realName("张三")
                .studentNo("2023001")
                .college("软件工程学院")
                .major("软件技术")
                .className("2301班")
                .totalScore(85)
                .fullScore(100)
                .submitted(true)
                .build();

        com.kece.exam.dto.ExamAdminOverviewVO overview = com.kece.exam.dto.ExamAdminOverviewVO.builder()
                .exam(exam)
                .students(List.of(row))
                .totalStudents(1)
                .submittedCount(1)
                .build();

        when(examAdminService.getAdminOverview(1L)).thenReturn(overview);

        ExamStudentPaperVO templatePaper = new ExamStudentPaperVO();
        templatePaper.setExam(exam);
        templatePaper.setQuestions(List.of());
        when(examAdminService.getStudentPaper(1L, null)).thenReturn(templatePaper);

        ExamStudentInfoVO studentInfo = new ExamStudentInfoVO();
        studentInfo.setRealName("张三");
        studentInfo.setStudentNo("2023001");
        ExamStudentPaperVO studentPaper = new ExamStudentPaperVO();
        studentPaper.setExam(exam);
        studentPaper.setUserId(100L);
        studentPaper.setStudentInfo(studentInfo);
        studentPaper.setQuestions(List.of());
        when(examAdminService.getStudentPaper(1L, 100L)).thenReturn(studentPaper);

        jakarta.servlet.http.HttpServletResponse response = mock(jakarta.servlet.http.HttpServletResponse.class);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        jakarta.servlet.ServletOutputStream sos = new jakarta.servlet.ServletOutputStream() {
            @Override
            public boolean isReady() { return true; }
            @Override
            public void setWriteListener(jakarta.servlet.WriteListener writeListener) {}
            @Override
            public void write(int b) { baos.write(b); }
            @Override
            public void write(byte[] b, int off, int len) { baos.write(b, off, len); }
        };
        when(response.getOutputStream()).thenReturn(sos);

        exportService.exportBatchZip(1L, null, response);

        byte[] zipBytes = baos.toByteArray();
        assertThat(zipBytes.length).isGreaterThan(0);

        java.util.List<String> entryNames = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                entryNames.add(ze.getName());
            }
        }

        assertThat(entryNames).anyMatch(name -> name.contains("标准空白卷.doc"));
        assertThat(entryNames).anyMatch(name -> name.contains("张三_2023001_答卷.doc"));
    }
}
