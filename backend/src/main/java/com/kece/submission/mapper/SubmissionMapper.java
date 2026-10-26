package com.kece.submission.mapper;

import com.kece.submission.dto.SubmissionListVO;
import com.kece.submission.entity.Submission;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提交记录 Mapper。 */
@Mapper
public interface SubmissionMapper {

    int insert(Submission submission);

    Submission findById(Long id);

    List<SubmissionListVO> findPage(
            @Param("userId") Long userId,
            @Param("questionId") Long questionId,
            @Param("status") String status,
            @Param("createdBy") Long createdBy,
            @Param("offset") int offset,
            @Param("size") int size);

    long count(
            @Param("userId") Long userId,
            @Param("questionId") Long questionId,
            @Param("status") String status,
            @Param("createdBy") Long createdBy);

    /** 当前用户已 AC 的题目 id（去重）。 */
    List<Long> findAcceptedQuestionIds(@Param("userId") Long userId);

    /** 题目是否存在提交记录（删除保护）。 */
    boolean existsByQuestionId(@Param("questionId") Long questionId);

    /** 删除某题目的全部提交记录（ADMIN 强制删除题目时级联清理）。 */
    int deleteByQuestionId(@Param("questionId") Long questionId);

    /** 按考试场次+考生+题目查最新提交（考试成绩汇总用）。 */
    Submission findLatestByContest(
            @Param("contestId") Long contestId,
            @Param("userId") Long userId,
            @Param("questionId") Long questionId);

    /** 按考试场次查全部提交，按 id 升序（ACM 罚时统计用）。 */
    List<Submission> findByContest(@Param("contestId") Long contestId);

    /** 删除某考试的全部代码提交记录（ADMIN 强制删除考试时级联清理）。 */
    int deleteByContest(@Param("contestId") Long contestId);

    /** 重判前重置评测结果：状态回 PENDING 并清空全部结果字段。 */
    int resetResult(@Param("id") Long id);

    int updateResult(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("score") Integer score,
            @Param("timeUsedMs") Integer timeUsedMs,
            @Param("memoryUsedKb") Integer memoryUsedKb,
            @Param("errorLog") String errorLog,
            @Param("resultJson") String resultJson);
}
