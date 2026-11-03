package com.kece.practice.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课练题目条目实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonPracticeQuestion {

    private Long id;
    private Long practiceId;
    private Long questionId;
    private String questionType; // OBJECTIVE, ALGO, DB
    private String title;
    private Integer score;
    private Integer sortOrder;
    private String snapshotJson; // 题目核心元数据快照
    private LocalDateTime createdAt;
}
