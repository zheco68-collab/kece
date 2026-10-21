package com.kece.rank.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 排行榜分页响应视图。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankPageVO {

    /** 当前榜单分类代码（ALGO / SQL / OBJECTIVE） */
    private String type;

    /** 分类中文标签名称 */
    private String typeLabel;

    /** 上榜总人数 */
    private Long total;

    /** 当前页码（1 起始） */
    private Integer page;

    /** 每页条数 */
    private Integer size;

    /** 排行榜用户列表 */
    private List<RankUserVO> list;

    /** 当前登录用户自身的排名卡片（未登录或未参与则为 null） */
    private RankUserVO myRank;
}
