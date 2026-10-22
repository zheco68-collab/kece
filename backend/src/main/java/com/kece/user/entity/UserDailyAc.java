package com.kece.user.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户每日 AC 活跃打卡实体，映射 {@code user_daily_ac} 表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDailyAc {

    private Long id;
    private Long userId;
    private LocalDate acDate;
    private Integer algoCount;
    private Integer dbCount;
    private Integer totalCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
