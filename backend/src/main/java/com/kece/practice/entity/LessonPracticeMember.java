package com.kece.practice.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课练成员实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonPracticeMember {

    private Long id;
    private Long practiceId;
    private Long userId;
    private Integer totalScore;
    private Integer solvedCount;
    private Long penaltyTime; // 累计罚时(秒)
    private LocalDateTime joinedAt;
    private Integer submitted; // 1 已交卷, 0 进行中
    private LocalDateTime submittedAt;
    private LocalDateTime lastActiveAt; // 最后答题或心跳时间
}
