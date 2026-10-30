package com.kece.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 考生实名信息 VO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamStudentInfoVO {

    private Long id;
    private Long examId;
    private Long userId;
    private String realName;
    private String studentNo;
    private String college;
    private String major;
    private String className;
}
