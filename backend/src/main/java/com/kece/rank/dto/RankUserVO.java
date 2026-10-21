package com.kece.rank.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 排行榜单项用户数据视图。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankUserVO {

    /** 当前天梯名次（1 起始） */
    private Integer rank;

    /** 用户 ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 头像 URL */
    private String avatarUrl;

    /** 个性签名 */
    private String bio;

    /** 用户角色（逗号分隔或主角色，如 ADMIN/TEACHER/USER） */
    private String roles;

    /** 通过题目数（去重题数） */
    private Integer solvedCount;

    /** 提交/作答总次数 */
    private Integer totalSubmissions;

    /** 成功 AC / 正确次数 */
    private Integer acSubmissions;

    /** AC 通过率 / 正确率（百分比，保留 1 位小数，如 85.5） */
    private Double acceptRate;

    /** 累计总得分（主要用于客观题等具有分值属性的榜单） */
    private Integer totalScore;

    /** 最近一次 AC / 通过作答时间 */
    private LocalDateTime lastAcceptedAt;
}
