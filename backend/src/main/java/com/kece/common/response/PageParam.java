package com.kece.common.response;

/**
 * 统一分页参数与安全边界计算工具。
 * 约束页码 >= 1，单页大小在 1 到 100 之间，自动计算 offset。
 *
 * @param page 安全页码
 * @param size 安全单页大小
 * @param offset 数据库查询偏移量
 */
public record PageParam(int page, int size, int offset) {

    public static PageParam of(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new PageParam(safePage, safeSize, (safePage - 1) * safeSize);
    }
}
