package com.kece.question.strategy;

/**
 * 考试赛制下题目提交记录概要。
 *
 * @param submissionId 实际全局评测记录 ID
 * @param status 当前评测状态
 * @param score 评测得分（百分制）
 */
public record ContestSubmissionDTO(Long submissionId, String status, Integer score) {}
