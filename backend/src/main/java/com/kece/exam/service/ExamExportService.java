package com.kece.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.exam.dto.ExamAdminOverviewVO;
import com.kece.exam.dto.ExamStudentInfoVO;
import com.kece.exam.dto.ExamStudentPaperQuestionVO;
import com.kece.exam.dto.ExamStudentPaperVO;
import com.kece.exam.dto.ExamStudentSubmissionRowVO;
import com.kece.exam.dto.ExamVO;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 考试试卷与答卷文档导出服务（Word .doc 与打印版 PDF 格式支持）。 */
@Service
@RequiredArgsConstructor
public class ExamExportService {

    private final ExamAdminService examAdminService;
    private final ObjectMapper objectMapper;

    /** 导出符合学校标准格式的 Word (.doc) 文档（studentUserId 为空则导出空白标准 A 卷，非空则导出该考生的完整答卷及成绩）。 */
    public void exportWord(Long examId, Long studentUserId, HttpServletResponse response) throws IOException {
        ExamStudentPaperVO paper = examAdminService.getStudentPaper(examId, studentUserId);
        ExamVO exam = paper.getExam();
        ExamStudentInfoVO student = paper.getStudentInfo();

        String schoolName = exam.getSchoolName() != null && !exam.getSchoolName().isBlank()
                ? exam.getSchoolName()
                : "湖北科技职业学院";
        String semester = exam.getSemester() != null && !exam.getSemester().isBlank()
                ? exam.getSemester()
                : "2025 - 2026学年度第一学期期末考试";
        String examTitle = exam.getTitle() != null ? exam.getTitle() : "期末考试";

        String fileName;
        if (studentUserId != null && student != null && student.getRealName() != null) {
            fileName = "《" + examTitle + "》期末考试_" + student.getRealName() + "_" + student.getStudentNo() + "_答卷.doc";
        } else {
            fileName = "《" + examTitle + "》期末考试A卷.doc";
        }

        String htmlContent = buildWordHtml(paper, schoolName, semester, examTitle, false);

        response.setContentType("application/msword; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        String encodedFilename = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.getOutputStream().write(htmlContent.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /** 导出适合直接浏览器打印/保存为标准 A4 PDF 的 HTML 视图流。 */
    public String getPrintableHtml(Long examId, Long studentUserId) {
        ExamStudentPaperVO paper = examAdminService.getStudentPaper(examId, studentUserId);
        ExamVO exam = paper.getExam();
        String schoolName = exam.getSchoolName() != null && !exam.getSchoolName().isBlank()
                ? exam.getSchoolName()
                : "湖北科技职业学院";
        String semester = exam.getSemester() != null && !exam.getSemester().isBlank()
                ? exam.getSemester()
                : "2025 - 2026学年度第一学期期末考试";
        String examTitle = exam.getTitle() != null ? exam.getTitle() : "期末考试";

        return buildWordHtml(paper, schoolName, semester, examTitle, true);
    }

    /** 批量导出全部或指定考生的 Word (.doc) 答卷压缩包 (.zip)。 */
    public void exportBatchZip(Long examId, List<Long> studentUserIds, HttpServletResponse response) throws IOException {
        ExamAdminOverviewVO overview = examAdminService.getAdminOverview(examId);
        ExamVO exam = overview.getExam();
        String schoolName = exam.getSchoolName() != null && !exam.getSchoolName().isBlank()
                ? exam.getSchoolName()
                : "湖北科技职业学院";
        String semester = exam.getSemester() != null && !exam.getSemester().isBlank()
                ? exam.getSemester()
                : "2025 - 2026学年度第一学期期末考试";
        String examTitle = exam.getTitle() != null ? exam.getTitle() : "期末考试";

        List<ExamStudentSubmissionRowVO> students = overview.getStudents();
        if (studentUserIds != null && !studentUserIds.isEmpty()) {
            Set<Long> filterSet = new HashSet<>(studentUserIds);
            students = students.stream().filter(s -> filterSet.contains(s.getUserId())).toList();
        }

        if (students == null || students.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前考试暂无考生作答记录，无法批量导出");
        }

        String zipFileName = "《" + examTitle + "》期末考试_全部学生试卷.zip";
        if (studentUserIds != null && !studentUserIds.isEmpty() && overview.getStudents() != null && students.size() != overview.getStudents().size()) {
            zipFileName = "《" + examTitle + "》期末考试_指定考生试卷(" + students.size() + "份).zip";
        }
        zipFileName = sanitizeFileName(zipFileName);

        response.setContentType("application/zip");
        response.setCharacterEncoding("UTF-8");
        String encodedFilename = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            // 将空白标准 A 卷也存入压缩包根目录，方便教师对照原卷
            try {
                ExamStudentPaperVO templatePaper = examAdminService.getStudentPaper(examId, null);
                String templateHtml = buildWordHtml(templatePaper, schoolName, semester, examTitle, false);
                byte[] templateBytes = templateHtml.getBytes(StandardCharsets.UTF_8);
                String templateName = sanitizeFileName("00_《" + examTitle + "》期末考试A卷_标准空白卷.doc");
                ZipEntry templateEntry = new ZipEntry(templateName);
                templateEntry.setSize(templateBytes.length);
                zos.putNextEntry(templateEntry);
                zos.write(templateBytes);
                zos.closeEntry();
            } catch (Exception ignored) {
                // 空白卷构建异常不阻断考生答卷导出
            }

            Set<String> usedFileNames = new HashSet<>();
            for (ExamStudentSubmissionRowVO studentRow : students) {
                Long uid = studentRow.getUserId();
                ExamStudentPaperVO paper = examAdminService.getStudentPaper(examId, uid);
                ExamStudentInfoVO info = paper.getStudentInfo();

                String studentName = (info != null && info.getRealName() != null && !info.getRealName().isBlank())
                        ? info.getRealName().trim()
                        : (studentRow.getRealName() != null && !studentRow.getRealName().isBlank() && !"—".equals(studentRow.getRealName()) ? studentRow.getRealName().trim() : studentRow.getUsername());
                String studentNo = (info != null && info.getStudentNo() != null && !info.getStudentNo().isBlank())
                        ? info.getStudentNo().trim()
                        : (studentRow.getStudentNo() != null && !studentRow.getStudentNo().isBlank() && !"—".equals(studentRow.getStudentNo()) ? studentRow.getStudentNo().trim() : "UID" + uid);

                String baseDocName = "《" + examTitle + "》期末考试_" + studentName + "_" + studentNo + "_答卷.doc";
                baseDocName = sanitizeFileName(baseDocName);
                if (usedFileNames.contains(baseDocName)) {
                    baseDocName = sanitizeFileName("《" + examTitle + "》期末考试_" + studentName + "_" + studentNo + "_uid" + uid + "_答卷.doc");
                }
                usedFileNames.add(baseDocName);

                String htmlContent = buildWordHtml(paper, schoolName, semester, examTitle, false);
                byte[] bytes = htmlContent.getBytes(StandardCharsets.UTF_8);

                ZipEntry entry = new ZipEntry(baseDocName);
                entry.setSize(bytes.length);
                zos.putNextEntry(entry);
                zos.write(bytes);
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "exam_paper.doc";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String buildWordHtml(ExamStudentPaperVO paper, String schoolName, String semester, String examTitle, boolean isPrintMode) {
        ExamVO exam = paper.getExam();
        ExamStudentInfoVO student = paper.getStudentInfo();
        boolean isStudentPaper = paper.getUserId() != null;

        // 严格对齐 AdminStudentPaperView.vue 的大题分组逻辑
        Map<String, List<ExamStudentPaperQuestionVO>> groups = new LinkedHashMap<>();
        groups.put("选择题", new ArrayList<>());
        groups.put("判断题", new ArrayList<>());
        groups.put("填空题", new ArrayList<>());
        groups.put("数据库 SQL 实操题", new ArrayList<>());
        groups.put("编程算法实操题", new ArrayList<>());

        if (paper.getQuestions() != null) {
            for (ExamStudentPaperQuestionVO q : paper.getQuestions()) {
                if ("OBJECTIVE".equals(q.getQuestionType())) {
                    if ("SINGLE".equals(q.getObjectiveType()) || "MULTIPLE".equals(q.getObjectiveType())) {
                        groups.get("选择题").add(q);
                    } else if ("JUDGE".equals(q.getObjectiveType())) {
                        groups.get("判断题").add(q);
                    } else if ("FILL".equals(q.getObjectiveType())) {
                        groups.get("填空题").add(q);
                    } else {
                        groups.get("选择题").add(q);
                    }
                } else if ("DB".equals(q.getQuestionType()) || "DATABASE".equals(q.getQuestionType())) {
                    groups.get("数据库 SQL 实操题").add(q);
                } else {
                    groups.get("编程算法实操题").add(q);
                }
            }
        }

        // 过滤非空大题分组，赋予序号（一、二、三...）
        String[] chineseNums = {"一", "二", "三", "四", "五", "六", "七", "八"};
        List<SectionGroup> validSections = new ArrayList<>();
        int sIdx = 0;
        for (Map.Entry<String, List<ExamStudentPaperQuestionVO>> entry : groups.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                String cNum = sIdx < chineseNums.length ? chineseNums[sIdx] : String.valueOf(sIdx + 1);
                String title = cNum + "、" + entry.getKey();
                int full = entry.getValue().stream().mapToInt(ExamStudentPaperQuestionVO::getFullScore).sum();
                int earned = entry.getValue().stream().mapToInt(q -> q.getStudentScore() != null ? q.getStudentScore() : 0).sum();
                validSections.add(new SectionGroup(title, cNum, entry.getValue(), full, earned));
                sIdx++;
            }
        }

        int paperFullScore = paper.getFullScore() != null ? paper.getFullScore()
                : validSections.stream().mapToInt(s -> s.fullScore).sum();
        int paperTotalScore = paper.getTotalScore() != null ? paper.getTotalScore()
                : validSections.stream().mapToInt(s -> s.earnedScore).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html xmlns:v=\"urn:schemas-microsoft-com:vml\"\n");
        sb.append("xmlns:o=\"urn:schemas-microsoft-com:office:office\"\n");
        sb.append("xmlns:w=\"urn:schemas-microsoft-com:office:word\"\n");
        sb.append("xmlns=\"http://www.w3.org/TR/REC-html40\">\n");
        sb.append("<head>\n");
        sb.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
        sb.append("<title>").append(escapeHtml(examTitle)).append("</title>\n");
        sb.append("<!--[if gte mso 9]>\n");
        sb.append("<xml>\n");
        sb.append("<w:WordDocument>\n");
        sb.append("<w:View>Print</w:View>\n");
        sb.append("<w:Zoom>100</w:Zoom>\n");
        sb.append("<w:DoNotOptimizeForBrowser/>\n");
        sb.append("</w:WordDocument>\n");
        sb.append("</xml>\n");
        sb.append("<![endif]-->\n");
        sb.append("<style>\n");
        sb.append("@page { size: 595.3pt 841.9pt; margin: 72.0pt 90.0pt 72.0pt 90.0pt; mso-header-margin: 35.4pt; mso-footer-margin: 35.4pt; }\n");
        sb.append("body, html { background: #ffffff !important; color: #000000; font-family: '楷体', 'SimSun', '宋体', serif; font-size: 10.5pt; line-height: 1.5; margin: 0; padding: 0; }\n");
        sb.append(".paper-sheet { position: relative; max-width: 820px; margin: 0 auto; background: #ffffff; padding: 0; color: #000; font-family: '楷体', 'SimSun', '宋体', serif; }\n");
        sb.append(".top-tag { text-align: left; font-size: 12pt; font-family: '楷体', serif; margin-bottom: 4pt; }\n");
        sb.append(".school-header { font-family: '楷体', serif; font-size: 16pt; font-weight: bold; text-align: center; margin: 0 0 4pt 0; white-space: nowrap; }\n");
        sb.append(".exam-title { font-family: '楷体', serif; font-size: 15pt; font-weight: bold; text-align: center; margin: 0 0 14pt 0; }\n");
        sb.append(".exam-score-table { width: 100%; border-collapse: collapse; text-align: center; margin-bottom: 16pt; }\n");
        sb.append(".exam-score-table th, .exam-score-table td { border: 1pt solid #000; padding: 4pt 2pt; font-size: 11pt; font-family: '楷体', serif; height: 19pt; }\n");
        sb.append(".th-title, .td-row-name { font-weight: bold; width: 14%; }\n");
        sb.append(".th-total { width: 14%; }\n");
        sb.append(".td-total-full { font-weight: bold; }\n");
        sb.append(".earned-score-cell { font-weight: bold; color: #1971c2; }\n");
        sb.append(".td-total-earned { font-weight: bold; color: #d9480f; font-size: 12pt; }\n");
        sb.append(".section-heading-box { margin: 14pt 0 6pt 0; page-break-after: avoid; }\n");
        sb.append(".mini-score-table { border-collapse: collapse; text-align: center; margin-bottom: 4pt; }\n");
        sb.append(".mini-score-table td { border: 1pt solid #000; padding: 2pt 6pt; font-size: 10.5pt; font-family: '楷体', serif; width: 42pt; height: 16pt; }\n");
        sb.append(".mini-score-cell { font-weight: bold; color: #1971c2; }\n");
        sb.append(".sec-title { font-family: '楷体', serif; font-size: 12pt; font-weight: bold; margin: 4pt 0 6pt 0; }\n");
        sb.append(".paper-q-card { margin-bottom: 8pt; page-break-inside: avoid; }\n");
        sb.append(".q-title { font-family: '楷体', serif; font-size: 10.5pt; margin: 5pt 0 3pt 0; line-height: 1.45; }\n");
        sb.append(".student-choice { font-weight: bold; text-decoration: underline; padding: 0 4pt; }\n");
        sb.append(".ans-eval { font-size: 10pt; font-family: '楷体', serif; margin-left: 8pt; white-space: nowrap; }\n");
        sb.append(".eval-ac { color: #2b8a3e; font-weight: bold; }\n");
        sb.append(".eval-partial { color: #e67700; font-weight: bold; }\n");
        sb.append(".eval-fail { color: #c92a2a; font-weight: bold; }\n");
        sb.append(".q-options { font-family: '楷体', serif; font-size: 10.5pt; margin: 2pt 0 6pt 16pt; line-height: 1.4; }\n");
        sb.append(".q-content { font-family: '楷体', serif; font-size: 10.5pt; line-height: 1.5; margin: 4pt 0 6pt 0; color: #222; }\n");
        sb.append(".code-box { border: 1pt solid #777; background: #fbfbfb; padding: 6pt 10pt; margin: 6pt 0; }\n");
        sb.append(".code-header { font-weight: bold; font-size: 10pt; margin-bottom: 3pt; color: #333; }\n");
        sb.append(".code-content { margin: 0; font-size: 9.5pt; line-height: 1.4; white-space: pre-wrap; word-break: break-all; font-family: 'Consolas', 'Courier New', monospace; color: #111; }\n");
        sb.append(".std-box { border-left: 3pt solid #1971c2; background: #f8f9fa; padding: 4pt 10pt; margin: 6pt 0; font-size: 10pt; }\n");
        sb.append(".blank-answer-box { border: 1pt solid #777; background: #fff; height: 90pt; margin: 6pt 0; padding: 6pt; }\n");
        sb.append(".paper-footer-criteria { margin-top: 24pt; page-break-inside: avoid; }\n");
        sb.append(".footer-criteria-title { font-family: '楷体', serif; font-size: 12pt; font-weight: bold; margin-bottom: 6pt; }\n");
        sb.append(".criteria-table { width: 100%; border-collapse: collapse; }\n");
        sb.append(".criteria-table th, .criteria-table td { border: 1pt solid #000; padding: 4pt 8pt; font-size: 10pt; font-family: '楷体', serif; }\n");
        sb.append(".criteria-table th { background: #f4f5f7; text-align: center; }\n");
        sb.append(".total-row { font-weight: bold; }\n");
        sb.append("@media screen { .web-sidebar { position: fixed; left: 24px; top: 100px; display: flex; gap: 14px; z-index: 10; } .paper-sheet { margin-left: 140px; } }\n");
        sb.append("@media print { @page { size: A4 portrait; margin: 15mm 15mm 15mm 30mm; } body, html { background: #ffffff !important; } .no-print { display: none !important; } .paper-sheet { box-shadow: none !important; border: none !important; margin: 0 !important; padding: 0 !important; max-width: 100% !important; } .web-sidebar { position: fixed; left: 0; top: 60px; display: flex; gap: 10px; } }\n");
        sb.append(".web-sidebar-info { writing-mode: vertical-rl; -webkit-writing-mode: vertical-rl; transform: rotate(180deg); font-family: '宋体', serif; font-size: 13px; letter-spacing: 2px; line-height: 1.6; height: 550px; }\n");
        sb.append(".web-sidebar-seal { writing-mode: vertical-rl; -webkit-writing-mode: vertical-rl; font-family: '宋体', serif; font-size: 14px; letter-spacing: 20px; border-left: 1px solid #000; border-right: 1px solid #000; padding: 0 4px; height: 600px; text-align: center; }\n");
        sb.append("</style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");

        if (isPrintMode) {
            sb.append("<div class=\"no-print\" style=\"position: sticky; top: 0; background: #1e1e1e; color: #fff; padding: 10px 20px; display: flex; justify-content: space-between; align-items: center; z-index: 999; box-shadow: 0 2px 8px rgba(0,0,0,0.2);\">\n");
            sb.append("  <div style=\"font-size: 13px; font-weight: bold;\">📄 试卷标准化打印 / PDF 导出预览 (可在调起对话框中选择「另存为 PDF」)</div>\n");
            sb.append("  <button onclick=\"window.print()\" style=\"background: #228be6; color: #fff; border: none; padding: 6px 16px; font-size: 13px; font-weight: bold; cursor: pointer; border-radius: 4px;\">🖨️ 立即打印 / 另存为 PDF</button>\n");
            sb.append("</div>\n");
        }

        String realName = student != null && student.getRealName() != null ? student.getRealName()
                : (paper.getUsername() != null ? paper.getUsername() : "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;");
        String studentNo = student != null && student.getStudentNo() != null ? student.getStudentNo() : "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;";
        String college = student != null && student.getCollege() != null ? student.getCollege() : "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;";
        String major = "";
        if (student != null) {
            if (student.getMajor() != null && !student.getMajor().isBlank()) {
                major += student.getMajor().trim();
            }
            if (student.getClassName() != null && !student.getClassName().isBlank()) {
                if (!major.isEmpty()) {
                    major += " ";
                }
                major += student.getClassName().trim();
            }
        }
        if (major.isEmpty()) {
            major = "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;";
        }

        // 1. Word 原生 VML 左侧密封线与考生信息栏（Word 打开时原生停靠在左页边距）
        sb.append("<!--[if gte vml 1]>\n");
        sb.append("<v:shapetype id=\"_x0000_t202\" coordsize=\"21600,21600\" o:spt=\"202\" path=\"m,l,21600r21600,l21600,xe\">\n");
        sb.append(" <v:stroke joinstyle=\"miter\"/>\n");
        sb.append(" <v:path gradientshapeok=\"t\" o:connecttype=\"rect\"/>\n");
        sb.append("</v:shapetype>\n");
        sb.append("<v:shape id=\"Seal_Line_Box\" o:spid=\"_x0000_s2054\" type=\"#_x0000_t202\"\n");
        sb.append(" style='position:absolute;left:0;text-align:left;margin-left:-51.45pt;margin-top:-4.2pt;width:36.75pt;height:694.2pt;z-index:251658240' stroked=\"f\">\n");
        sb.append(" <v:textbox>\n");
        sb.append("    <table border=1 cellspacing=0 cellpadding=0 style='margin-left:5.4pt;border-collapse:collapse;border:none;'>\n");
        sb.append("     <tr style='height:690.8pt;'>\n");
        sb.append("      <td width=32 style='width:24.0pt;border-top:none;border-left:solid windowtext 1.0pt;border-bottom:none;border-right:solid windowtext 1.0pt;padding:0cm 5.4pt;writing-mode:sideways-lr;height:690.8pt;'>\n");
        sb.append("        <p align=center style='text-align:center;'><span style='font-family:宋体;letter-spacing:20.0pt;'>密封线内禁止答题</span></p>\n");
        sb.append("      </td>\n");
        sb.append("     </tr>\n");
        sb.append("    </table>\n");
        sb.append(" </v:textbox>\n");
        sb.append("</v:shape>\n");
        sb.append("<v:shape id=\"Student_Info_Box\" o:spid=\"_x0000_s2053\" type=\"#_x0000_t202\"\n");
        sb.append(" style='position:absolute;left:0;text-align:left;margin-left:-82.95pt;margin-top:40.0pt;width:31.5pt;height:580.0pt;z-index:251657216' stroked=\"f\">\n");
        sb.append(" <v:textbox style='layout-flow:vertical;mso-layout-flow-alt:bottom-to-top;'>\n");
        sb.append("  <div>\n");
        sb.append("    <p style='text-indent:10.0pt;font-size:10.5pt;line-height:12pt;font-family:宋体;'><span style='font-family:宋体;'>姓名：</span><u>&nbsp;").append(escapeHtml(realName)).append("&nbsp;</u>&nbsp;&nbsp;<span style='font-family:宋体;'>学号：</span><u>&nbsp;").append(escapeHtml(studentNo)).append("&nbsp;</u>&nbsp;&nbsp;<span style='font-family:宋体;'>学院：</span><u>&nbsp;").append(escapeHtml(college)).append("&nbsp;</u>&nbsp;&nbsp;<span style='font-family:宋体;'>年级、专业：</span><u>&nbsp;").append(escapeHtml(major)).append("&nbsp;</u></p>\n");
        sb.append("  </div>\n");
        sb.append(" </v:textbox>\n");
        sb.append("</v:shape>\n");
        sb.append("<![endif]-->\n");

        // 2. 网页端 / PDF 打印端左侧纵向密封栏
        sb.append("<![if !mso]>\n");
        sb.append("<div class=\"web-sidebar\">\n");
        sb.append("  <div class=\"web-sidebar-info\">\n");
        sb.append("    姓名：<u>&nbsp;").append(escapeHtml(realName)).append("&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;学号：<u>&nbsp;").append(escapeHtml(studentNo)).append("&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;学院：<u>&nbsp;").append(escapeHtml(college)).append("&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;年级、专业：<u>&nbsp;").append(escapeHtml(major)).append("&nbsp;</u>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"web-sidebar-seal\">\n");
        sb.append("    密封线内禁止答题\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");
        sb.append("<![endif]>\n");

        sb.append("<main class=\"paper-sheet\">\n");

        // 3. 附件头
        sb.append("  <div class=\"top-tag\">附件6</div>\n");

        // 4. 试卷大标题
        sb.append("  <div class=\"school-header\">").append(escapeHtml(schoolName)).append(" ").append(escapeHtml(semester)).append("</div>\n");
        sb.append("  <div class=\"exam-title\">《").append(escapeHtml(examTitle)).append("》试题 A 卷（").append(escapeHtml(exam != null && exam.getMode() != null ? exam.getMode() : "开卷")).append("）</div>\n");

        // 5. 大题得分总表
        sb.append("  <table class=\"exam-score-table\">\n");
        sb.append("    <thead>\n      <tr>\n        <th class=\"th-title\">题&nbsp;&nbsp;号</th>\n");
        for (SectionGroup sec : validSections) {
            sb.append("        <th>").append(escapeHtml(sec.shortName)).append("</th>\n");
        }
        sb.append("        <th class=\"th-total\">总&nbsp;&nbsp;分</th>\n      </tr>\n    </thead>\n");
        sb.append("    <tbody>\n");

        if (isStudentPaper) {
            // 卷面满分行
            sb.append("      <tr>\n        <td class=\"td-row-name\">卷面满分</td>\n");
            for (SectionGroup sec : validSections) {
                sb.append("        <td>").append(sec.fullScore).append("</td>\n");
            }
            sb.append("        <td class=\"td-total-full\">").append(paperFullScore).append("</td>\n      </tr>\n");

            // 考生实得行
            sb.append("      <tr>\n        <td class=\"td-row-name\">考生实得</td>\n");
            for (SectionGroup sec : validSections) {
                sb.append("        <td class=\"earned-score-cell\">").append(sec.earnedScore).append("</td>\n");
            }
            sb.append("        <td class=\"td-total-earned\">").append(paperTotalScore).append("</td>\n      </tr>\n");

            // 评卷人行
            sb.append("      <tr>\n        <td class=\"td-row-name\">评卷人</td>\n");
            for (int i = 0; i < validSections.size(); i++) {
                sb.append("        <td>&nbsp;</td>\n");
            }
            sb.append("        <td>&nbsp;</td>\n      </tr>\n");
        } else {
            // 空白试卷标准两行排版（题号 + 得分）
            sb.append("      <tr>\n        <td class=\"td-row-name\">得&nbsp;&nbsp;分</td>\n");
            for (int i = 0; i < validSections.size(); i++) {
                sb.append("        <td>&nbsp;</td>\n");
            }
            sb.append("        <td>&nbsp;</td>\n      </tr>\n");
        }
        sb.append("    </tbody>\n  </table>\n");

        // 6. 试卷各大题试题列表
        for (SectionGroup sec : validSections) {
            sb.append("  <div class=\"section-heading-box\">\n");
            sb.append("    <table class=\"mini-score-table\">\n      <tr><td>得 分</td><td>评卷人</td></tr>\n      <tr>\n");
            if (isStudentPaper) {
                sb.append("        <td class=\"mini-score-cell\">").append(sec.earnedScore).append("</td>\n");
            } else {
                sb.append("        <td>&nbsp;</td>\n");
            }
            sb.append("        <td>&nbsp;</td>\n      </tr>\n    </table>\n");
            sb.append("    <div class=\"sec-title\">").append(escapeHtml(sec.sectionTitle))
              .append("（共 ").append(sec.questions.size()).append(" 题，共 ").append(sec.fullScore).append(" 分");
            if (isStudentPaper) {
                sb.append("，实得 ").append(sec.earnedScore).append(" 分");
            }
            sb.append("）</div>\n  </div>\n");

            for (int qIdx = 0; qIdx < sec.questions.size(); qIdx++) {
                ExamStudentPaperQuestionVO q = sec.questions.get(qIdx);
                String seqNo = (qIdx + 1) + "、";
                String rawTitle = (q.getContent() != null && !q.getContent().isBlank() && "OBJECTIVE".equals(q.getQuestionType()))
                        ? q.getContent().trim()
                        : (q.getTitle() != null ? q.getTitle().trim() : "");
                String studentAns = q.getStudentAnswer() != null ? q.getStudentAnswer().trim() : "";
                String stdAns = q.getStandardAnswer() != null ? q.getStandardAnswer().trim() : "";

                sb.append("  <article class=\"paper-q-card\">\n");

                if ("OBJECTIVE".equals(q.getQuestionType())) {
                    String cleanText = escapeHtml(rawTitle)
                            .replaceAll("`([^`]+)`", "$1")
                            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1");

                    if (isStudentPaper) {
                        String ansChoiceHtml = "<span class=\"student-choice\">" + escapeHtml(studentAns.isEmpty() ? "未作答" : studentAns) + "</span>";
                        if ("SINGLE".equals(q.getObjectiveType()) || "MULTIPLE".equals(q.getObjectiveType()) || "JUDGE".equals(q.getObjectiveType())) {
                            if (cleanText.matches(".*[（(]\\s*[)）].*")) {
                                cleanText = cleanText.replaceFirst("[（(]\\s*[)）]", "（ " + ansChoiceHtml + " ）");
                            } else {
                                cleanText += "（ " + ansChoiceHtml + " ）";
                            }
                        } else if ("FILL".equals(q.getObjectiveType())) {
                            cleanText += "&nbsp;&nbsp;<b>【考生作答：<u>&nbsp;" + escapeHtml(studentAns.isEmpty() ? "未作答" : studentAns) + "&nbsp;</u>】</b>";
                        }

                        if (!cleanText.matches(".*[（(]\\s*\\d+\\s*分[)）].*")) {
                            cleanText += "（" + q.getFullScore() + "分）";
                        }

                        // 判分评定标签
                        String evalHtml;
                        if (Boolean.TRUE.equals(q.getIsCorrect())) {
                            evalHtml = "<span class=\"ans-eval eval-ac\">[✔ 得满分: " + (q.getStudentScore() != null ? q.getStudentScore() : q.getFullScore()) + "分]</span>";
                        } else if (q.getStudentScore() != null && q.getStudentScore() > 0) {
                            evalHtml = "<span class=\"ans-eval eval-partial\">[◐ 得" + q.getStudentScore() + "/" + q.getFullScore() + "分，正确答案: " + escapeHtml(stdAns) + "]</span>";
                        } else {
                            String reason = studentAns.isEmpty() ? " (未作答)" : "";
                            evalHtml = "<span class=\"ans-eval eval-fail\">[✕ 扣" + q.getFullScore() + "分" + reason + "，正确答案: " + escapeHtml(stdAns.isEmpty() ? "—" : stdAns) + "]</span>";
                        }

                        sb.append("    <div class=\"q-title\">").append(seqNo).append(cleanText).append(evalHtml).append("</div>\n");

                        // 选项 (选择题)
                        if (q.getOptions() != null && !q.getOptions().isEmpty() && ("SINGLE".equals(q.getObjectiveType()) || "MULTIPLE".equals(q.getObjectiveType()))) {
                            sb.append(renderOptionsHtml(q.getOptions()));
                        }
                    } else {
                        // 空白试卷
                        if ("SINGLE".equals(q.getObjectiveType()) || "MULTIPLE".equals(q.getObjectiveType()) || "JUDGE".equals(q.getObjectiveType())) {
                            if (!cleanText.matches(".*[（(]\\s*[)）].*")) {
                                cleanText += "（&nbsp;&nbsp;&nbsp;&nbsp;）";
                            }
                        }
                        if (!cleanText.matches(".*[（(]\\s*\\d+\\s*分[)）].*")) {
                            cleanText += "（" + q.getFullScore() + "分）";
                        }
                        sb.append("    <div class=\"q-title\">").append(seqNo).append(cleanText).append("</div>\n");
                        if (q.getOptions() != null && !q.getOptions().isEmpty() && ("SINGLE".equals(q.getObjectiveType()) || "MULTIPLE".equals(q.getObjectiveType()))) {
                            sb.append(renderOptionsHtml(q.getOptions()));
                        } else if ("FILL".equals(q.getObjectiveType())) {
                            sb.append("    <div style=\"margin: 6pt 0;\">答：<u>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</u></div>\n");
                        }
                    }
                } else {
                    // 实践操作题 (DB / ALGO)
                    sb.append("    <div class=\"q-title\"><strong>").append(seqNo).append(escapeHtml(q.getTitle() != null ? q.getTitle().trim() : "")).append("</strong>（").append(q.getFullScore()).append("分）");
                    if (isStudentPaper) {
                        if (Boolean.TRUE.equals(q.getIsCorrect())) {
                            sb.append("<span class=\"ans-eval eval-ac\">[✔ 得满分: ").append(q.getStudentScore() != null ? q.getStudentScore() : q.getFullScore()).append("分]</span>");
                        } else if (q.getStudentScore() != null && q.getStudentScore() > 0) {
                            sb.append("<span class=\"ans-eval eval-partial\">[◐ 得").append(q.getStudentScore()).append("/").append(q.getFullScore()).append("分]</span>");
                        } else {
                            sb.append("<span class=\"ans-eval eval-fail\">[✕ 得0分]</span>");
                        }
                    }
                    sb.append("</div>\n");

                    if (q.getContent() != null && !q.getContent().isBlank() && !q.getContent().trim().equals(q.getTitle() != null ? q.getTitle().trim() : "")) {
                        sb.append("    <div class=\"q-content\">").append(cleanAndFormatMarkdown(q.getContent().trim())).append("</div>\n");
                    }

                    if (isStudentPaper) {
                        sb.append("    <div class=\"code-box\">\n");
                        sb.append("      <div class=\"code-header\">【考生作答内容快照】").append(q.getLanguage() != null && !q.getLanguage().isBlank() ? " (语言: " + escapeHtml(q.getLanguage()) + ")" : "").append("：</div>\n");
                        sb.append("      <pre class=\"code-content\">").append(formatRawStudentAnswer(studentAns, q.getQuestionType())).append("</pre>\n");
                        sb.append("    </div>\n");

                        if (!stdAns.isEmpty()) {
                            sb.append("    <div class=\"std-box\">\n");
                            sb.append("      <div class=\"code-header\">【参考解答 / 判分基准】：</div>\n");
                            sb.append("      <pre class=\"code-content\">").append(escapeHtml(stdAns)).append("</pre>\n");
                            sb.append("    </div>\n");
                        }
                    } else {
                        sb.append("    <div class=\"blank-answer-box\"><span style=\"color:#888; font-size:10pt;\">在此区域书写作答内容 / 核心代码：</span></div>\n");
                    }
                }

                sb.append("  </article>\n");
            }
        }

        // 7. 评分标准汇总表
        sb.append("  <div class=\"paper-footer-criteria\">\n");
        sb.append("    <div class=\"footer-criteria-title\">试卷评分标准与分值配比汇总</div>\n");
        sb.append("    <table class=\"criteria-table\">\n");
        sb.append("      <thead>\n        <tr>\n");
        sb.append("          <th style=\"width: 160px;\">评分项</th>\n");
        sb.append("          <th>评分标准说明</th>\n");
        sb.append("          <th style=\"width: 100px; text-align: center;\">分值</th>\n");
        sb.append("        </tr>\n      </thead>\n");
        sb.append("      <tbody>\n");
        for (SectionGroup sec : validSections) {
            sb.append("        <tr>\n");
            sb.append("          <td><strong>").append(escapeHtml(sec.sectionTitle)).append("</strong></td>\n");
            sb.append("          <td>共 ").append(sec.questions.size()).append(" 题，采用标准机考隔离沙箱与自动化测试集核验。</td>\n");
            sb.append("          <td style=\"text-align: center; font-weight: bold;\">").append(sec.fullScore).append(" 分</td>\n");
            sb.append("        </tr>\n");
        }
        sb.append("        <tr class=\"total-row\">\n");
        sb.append("          <td><strong>合&nbsp;&nbsp;计</strong></td>\n");
        sb.append("          <td>全卷综合标准化实操考评</td>\n");
        sb.append("          <td style=\"text-align: center; font-weight: bold; color: #1971c2;\">").append(paperFullScore).append(" 分</td>\n");
        sb.append("        </tr>\n");
        sb.append("      </tbody>\n    </table>\n");
        sb.append("  </div>\n");
        sb.append("</main>\n");

        if (isPrintMode) {
            sb.append("<script>\n");
            sb.append("window.addEventListener('load', function() {\n");
            sb.append("  setTimeout(function() { window.print(); }, 400);\n");
            sb.append("});\n");
            sb.append("</script>\n");
        }

        sb.append("</body>\n</html>\n");

        return sb.toString();
    }

    private static class SectionGroup {
        final String sectionTitle;
        final String shortName;
        final List<ExamStudentPaperQuestionVO> questions;
        final int fullScore;
        final int earnedScore;

        SectionGroup(String sectionTitle, String shortName, List<ExamStudentPaperQuestionVO> questions, int fullScore, int earnedScore) {
            this.sectionTitle = sectionTitle;
            this.shortName = shortName;
            this.questions = questions;
            this.fullScore = fullScore;
            this.earnedScore = earnedScore;
        }
    }

    /** 对题目 Markdown 格式进行试卷规范化清洗排版（去除 raw Markdown 标记及公式符号，规避 Word justify 拉伸问题）。 */
    public String cleanAndFormatMarkdown(String text) {
        if (text == null || text.isBlank()) return "";

        String content = escapeHtml(text).replace("\r\n", "\n").replace("\r", "\n");

        // 常见题目 Markdown 标题转为清晰小标题
        content = content.replaceAll("(?m)^#+\\s*(题目描述|问题描述)", "<div class=\"sub-title-box\">【题目描述】</div>");
        content = content.replaceAll("(?m)^#+\\s*(输入格式|输入说明|输入要求)", "<div class=\"sub-title-box\">【输入格式】</div>");
        content = content.replaceAll("(?m)^#+\\s*(输出格式|输出说明|输出要求)", "<div class=\"sub-title-box\">【输出格式】</div>");
        content = content.replaceAll("(?m)^#+\\s*(判分方式|判分说明|评分规则)", "<div class=\"sub-title-box\">【判分说明】</div>");
        content = content.replaceAll("(?m)^#+\\s*(样例输入|示例输入)", "<div class=\"sub-title-box\">【样例输入】</div>");
        content = content.replaceAll("(?m)^#+\\s*(样例输出|示例输出)", "<div class=\"sub-title-box\">【样例输出】</div>");
        content = content.replaceAll("(?m)^#+\\s*(数据范围|数据规模|提示|说明)", "<div class=\"sub-title-box\">【数据说明与提示】</div>");
        content = content.replaceAll("(?m)^#+\\s*(.+)", "<div class=\"sub-title-box\">【$1】</div>");

        // 常见数学符号与 LaTeX 替换
        content = content.replaceAll("\\$\\s*([^$]+)\\s*\\$", "$1");
        content = content.replace("\\le", "≤")
                         .replace("\\ge", "≥")
                         .replace("\\ne", "≠")
                         .replace("\\times", "×")
                         .replace("\\div", "÷")
                         .replace("\\approx", "≈")
                         .replace("\\cdots", "…")
                         .replace("\\ldots", "…")
                         .replace("-10^9", "-10⁹")
                         .replace("10^9", "10⁹")
                         .replace("10^5", "10⁵")
                         .replace("10^6", "10⁶")
                         .replace("10^18", "10¹⁸")
                         .replace("^2", "²")
                         .replace("^3", "³");

        // 行内粗体与行内代码
        content = content.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        content = content.replaceAll("`([^`]+)`", "<code class=\"inline-code\">$1</code>");

        // 按段落切分，包裹为带有左对齐与良好行高的标准段落
        String[] paragraphs = content.split("\n\n+");
        StringBuilder result = new StringBuilder();
        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("<div class=\"sub-title-box\">")) {
                result.append(p).append("\n");
            } else {
                result.append("  <p class=\"q-text-p\">").append(p.replace("\n", "<br>")).append("</p>\n");
            }
        }
        return result.toString();
    }

    /** 客观题选项规范紧凑排版（文本行排版，智能单行/双行/多行，规避 Word 表格残余浮动框）。 */
    public String renderOptionsHtml(List<String> options) {
        if (options == null || options.isEmpty()) return "";
        List<String> cleanOpts = new ArrayList<>();
        int totalChars = 0;
        for (int i = 0; i < options.size(); i++) {
            char letter = (char) ('A' + i);
            String cleanText = cleanOptionText(options.get(i));
            cleanOpts.add(letter + ". " + escapeHtml(cleanText));
            totalChars += cleanText.length();
        }

        if (totalChars <= 65 && cleanOpts.size() <= 4) {
            return "    <div class=\"q-options\">" + String.join("&nbsp;&nbsp;&nbsp;&nbsp;", cleanOpts) + "</div>\n";
        } else if (totalChars <= 110 && cleanOpts.size() <= 4) {
            String line1 = String.join("&nbsp;&nbsp;&nbsp;&nbsp;", cleanOpts.subList(0, 2));
            String line2 = String.join("&nbsp;&nbsp;&nbsp;&nbsp;", cleanOpts.subList(2, cleanOpts.size()));
            return "    <div class=\"q-options\">" + line1 + "<br>" + line2 + "</div>\n";
        } else {
            return "    <div class=\"q-options\">" + String.join("<br>\n", cleanOpts) + "</div>\n";
        }
    }

    /** 客观题选项表格排版（智能横向排布：短选项单行4列，中选项双行2列，长选项单列）。 */
    public String formatOptionsTable(List<String> options) {
        if (options == null || options.isEmpty()) return "";

        int maxLen = 0;
        List<String> cleanOptions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            String opt = options.get(i);
            String letter = String.valueOf((char) ('A' + i));
            String cleanText = opt.replaceFirst("^[A-Za-z][.、)）:：]\\s*", "").trim();
            cleanOptions.add("<strong>" + letter + ".</strong> " + escapeHtml(cleanText));
            maxLen = Math.max(maxLen, cleanText.length());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("  <table class=\"options-table\">\n");

        if (maxLen <= 16 && cleanOptions.size() <= 4) {
            // 单行 4 列
            sb.append("    <tr>\n");
            int colWidth = 100 / cleanOptions.size();
            for (String opt : cleanOptions) {
                sb.append("      <td style=\"width: ").append(colWidth).append("%;\">").append(opt).append("</td>\n");
            }
            sb.append("    </tr>\n");
        } else if (maxLen <= 36 && cleanOptions.size() <= 4) {
            // 双行 2 列
            for (int i = 0; i < cleanOptions.size(); i += 2) {
                sb.append("    <tr>\n");
                sb.append("      <td style=\"width: 50%;\">").append(cleanOptions.get(i)).append("</td>\n");
                if (i + 1 < cleanOptions.size()) {
                    sb.append("      <td style=\"width: 50%;\">").append(cleanOptions.get(i + 1)).append("</td>\n");
                } else {
                    sb.append("      <td style=\"width: 50%;\">&nbsp;</td>\n");
                }
                sb.append("    </tr>\n");
            }
        } else {
            // 单列
            for (String opt : cleanOptions) {
                sb.append("    <tr>\n      <td>").append(opt).append("</td>\n    </tr>\n");
            }
        }
        sb.append("  </table>\n");
        return sb.toString();
    }

    /** 格式化学生作答内容：将编程题 JSON 文件映射反序列化为规整的代码文本。 */
    public String formatStudentAnswerHtml(String rawAnswer, String questionType) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "  <div class=\"answer-box\"><strong>【考生作答】：</strong>（考生未作答）</div>\n";
        }
        String trimmed = rawAnswer.trim();

        // 编程题 JSON 文件格式解析
        if ("ALGO".equals(questionType) && trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, String> files = objectMapper.readValue(trimmed, new TypeReference<Map<String, String>>() {});
                if (!files.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  <div class=\"code-answer-box\">\n");
                    sb.append("    <div class=\"answer-label\">【考生作答代码】：</div>\n");
                    for (Map.Entry<String, String> entry : files.entrySet()) {
                        sb.append("    <div class=\"file-header\">// 文件: ").append(escapeHtml(entry.getKey())).append("</div>\n");
                        sb.append("    <pre class=\"code-content\">").append(escapeHtml(entry.getValue().trim())).append("</pre>\n");
                    }
                    sb.append("  </div>\n");
                    return sb.toString();
                }
            } catch (Exception ignored) {
                // 回退到普通显示
            }
        }

        // SQL 题
        if ("DB".equals(questionType) || "DATABASE".equals(questionType)) {
            return "  <div class=\"code-answer-box\">\n"
                 + "    <div class=\"answer-label\">【考生作答 SQL】：</div>\n"
                 + "    <pre class=\"code-content\">" + escapeHtml(trimmed) + "</pre>\n"
                 + "  </div>\n";
        }

        // 默认文本
        return "  <div class=\"answer-box\"><strong>【考生作答】：</strong>"
             + escapeHtml(trimmed)
             + "</div>\n";
    }

    /** 格式化参考解答。 */
    public String formatStandardAnswerHtml(String standardAnswer, String questionType) {
        if (standardAnswer == null || standardAnswer.isBlank()) return "";
        String trimmed = standardAnswer.trim();
        if ("DB".equals(questionType) || "DATABASE".equals(questionType)) {
            return "  <div class=\"standard-box\">\n"
                 + "    <strong>【参考解答 SQL】：</strong>\n"
                 + "    <pre class=\"code-content standard-code\">" + escapeHtml(trimmed) + "</pre>\n"
                 + "  </div>\n";
        }
        return "  <div class=\"standard-box\"><strong>【参考解答】：</strong>" + escapeHtml(trimmed) + "</div>\n";
    }

    public String cleanOptionText(String opt) {
        if (opt == null) return "";
        return opt.replaceFirst("^[A-Za-z][.、)）:：]\\s*", "").trim();
    }

    public String formatRawStudentAnswer(String rawAnswer, String questionType) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "（考生未提交作答）";
        }
        String trimmed = rawAnswer.trim();

        // 编程题 JSON 文件格式解析 (例如 {"Main.java": "..."})
        if ("ALGO".equals(questionType) && trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, String> files = objectMapper.readValue(trimmed, new TypeReference<Map<String, String>>() {});
                if (!files.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : files.entrySet()) {
                        sb.append("// 文件: ").append(entry.getKey()).append("\n");
                        sb.append(entry.getValue().trim()).append("\n\n");
                    }
                    return escapeHtml(sb.toString().trim());
                }
            } catch (Exception ignored) {
                // 回退到普通显示
            }
        }

        return escapeHtml(trimmed);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
