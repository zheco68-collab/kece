package com.kece.database.mapper;

import com.kece.database.dto.DbSubmissionListVO;
import com.kece.database.entity.DbSubmission;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据库提交记录 Mapper。 */
@Mapper
public interface DbSubmissionMapper {

    int insert(DbSubmission submission);

    DbSubmission findById(Long id);

    List<DbSubmissionListVO> findPage(
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

    /** 当前用户已 AC 的数据库题目 id（去重）。 */
    List<Long> findAcceptedQuestionIds(@Param("userId") Long userId);

    /** 题目是否存在提交记录（删除保护）。 */
    boolean existsByQuestionId(@Param("questionId") Long questionId);

    /** 删除某题目的全部提交记录（ADMIN 强制删除题目时级联清理）。 */
    int deleteByQuestionId(@Param("questionId") Long questionId);

    /** 按考试场次+考生+题目查最新提交（考试成绩汇总用）。 */
    DbSubmission findLatestByContest(
            @Param("contestId") Long contestId,
            @Param("userId") Long userId,
            @Param("questionId") Long questionId);

    /** 按考试场次查全部提交，按 id 升序（ACM 罚时统计用）。 */
    List<DbSubmission> findByContest(@Param("contestId") Long contestId);

    /** 删除某考试的全部数据库提交记录（ADMIN 强制删除考试时级联清理）。 */
    int deleteByContest(@Param("contestId") Long contestId);

    /** 重判前重置评测结果：状态回 PENDING 并清空全部结果字段。 */
    int resetResult(@Param("id") Long id);

    int updateResult(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("score") Integer score,
            @Param("timeUsedMs") Integer timeUsedMs,
            @Param("resultJson") String resultJson);
}
