package com.kece.practice.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课练作答流水实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonPracticeSubmission {

    private Long id;
    private Long practiceId;
    private Long practiceQuestionId;
    private Long userId;
    private String questionType; // OBJECTIVE, ALGO, DB
    private String answer;
    private Long submissionId;
    private Long bestSubmissionId;
    private String status; // ACCEPTED, PARTIAL, WRONG_ANSWER, PENDING, etc.
    private Integer score;
    private Integer attemptCount;
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime bestAchievedAt;
    private LocalDateTime updatedAt;
}
