package com.kece.practice.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课练详情视图。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeDetailVO {

    private Long id;
    private String title;
    private String description;
    private String accessCode;
    private Integer durationMinutes;
    private String status; // ACTIVE, ENDED
    private Long teacherId;
    private String teacherName;
    private Integer memberCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long remainingSeconds;

    /** 当前用户是否为该课练的授课教师或系统管理员（管理预览视角）。 */
    private Boolean canManage;

    /** 当前用户在该课练的状态。 */
    private Boolean myJoined;

    private Integer myScore;
    private Integer mySolvedCount;
    private Boolean mySubmitted;

    /** 分组题目：客观题卷 + 编程实操列表。 */
    private List<PracticeObjectiveQuestionVO> objectiveQuestions;

    private List<PracticePracticalQuestionVO> practicalQuestions;
}
