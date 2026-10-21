package com.kece.rank.dto;

/** 排行榜题型分类枚举。 */
public enum RankType {
    /** 编程算法题榜单 */
    ALGO,

    /** 数据库 SQL 题榜单 */
    SQL,

    /** 客观题积分榜单 */
    OBJECTIVE;

    public static RankType from(String val) {
        if (val == null || val.isBlank()) {
            return ALGO;
        }
        String upper = val.trim().toUpperCase();
        return switch (upper) {
            case "SQL", "DATABASE", "DB" -> SQL;
            case "OBJECTIVE", "OBJ" -> OBJECTIVE;
            default -> ALGO;
        };
    }
}
