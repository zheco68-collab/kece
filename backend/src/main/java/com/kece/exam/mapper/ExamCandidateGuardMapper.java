package com.kece.exam.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExamCandidateGuardMapper {

    // This upsert takes an exclusive row lock until the caller's transaction ends.
    @Insert(
            """
            INSERT INTO exam_candidate_guard (exam_id, user_id) VALUES (#{examId}, #{userId})
            ON DUPLICATE KEY UPDATE user_id = exam_candidate_guard.user_id
            """)
    void lock(@Param("examId") Long examId, @Param("userId") Long userId);

    @Select(
            """
            SELECT submitted_at IS NOT NULL FROM exam_candidate_guard
            WHERE exam_id = #{examId} AND user_id = #{userId}
            """)
    boolean isSubmitted(@Param("examId") Long examId, @Param("userId") Long userId);

    @Update(
            """
            UPDATE exam_candidate_guard SET submitted_at = UTC_TIMESTAMP(6)
            WHERE exam_id = #{examId} AND user_id = #{userId}
            """)
    void markSubmitted(@Param("examId") Long examId, @Param("userId") Long userId);

    @Select(
            """
            SELECT request_digest, CAST(response_json AS CHAR) AS response_json
            FROM exam_request_receipt
            WHERE exam_id = #{examId} AND user_id = #{userId} AND request_key = #{key}
            """)
    Receipt findReceipt(
            @Param("examId") Long examId, @Param("userId") Long userId, @Param("key") String key);

    @Insert(
            """
            INSERT INTO exam_request_receipt
                (exam_id, user_id, request_key, request_digest, response_json)
            VALUES (#{examId}, #{userId}, #{key}, #{digest}, #{response})
            """)
    void insertReceipt(
            @Param("examId") Long examId,
            @Param("userId") Long userId,
            @Param("key") String key,
            @Param("digest") String digest,
            @Param("response") String response);

    @Delete("DELETE FROM exam_candidate_guard WHERE exam_id = #{examId}")
    void deleteByExam(Long examId);

    record Receipt(String requestDigest, String responseJson) {}
}
