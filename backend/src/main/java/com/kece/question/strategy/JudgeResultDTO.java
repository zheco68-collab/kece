package com.kece.question.strategy;

/**
 * 题目评测判定结果标准传输对象。
 *
 * @param status 评测状态（如 ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, PENDING 等）
 * @param score 百分制原始得分（0 - 100）
 */
public record JudgeResultDTO(String status, Integer score) {}
