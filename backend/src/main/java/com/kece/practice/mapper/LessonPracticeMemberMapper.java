package com.kece.practice.mapper;

import com.kece.practice.entity.LessonPracticeMember;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LessonPracticeMemberMapper {

    @Insert(
            """
        INSERT INTO `lesson_practice_member` (practice_id, user_id, total_score, solved_count, penalty_time, joined_at, submitted)
        VALUES (#{practiceId}, #{userId}, #{totalScore}, #{solvedCount}, #{penaltyTime}, #{joinedAt}, #{submitted})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LessonPracticeMember member);

    @Select(
            "SELECT * FROM `lesson_practice_member` WHERE practice_id = #{practiceId} AND user_id = #{userId} LIMIT 1")
    LessonPracticeMember findByPracticeAndUser(
            @Param("practiceId") Long practiceId, @Param("userId") Long userId);

    @Select(
            """
            SELECT * FROM lesson_practice_member
            WHERE practice_id = #{practiceId} AND user_id = #{userId} FOR UPDATE
            """)
    LessonPracticeMember lockByPracticeAndUser(
            @Param("practiceId") Long practiceId, @Param("userId") Long userId);

    @Select(
            """
        SELECT * FROM `lesson_practice_member`
        WHERE practice_id = #{practiceId}
        ORDER BY total_score DESC, solved_count DESC, penalty_time ASC, joined_at ASC
    """)
    List<LessonPracticeMember> listByPracticeId(Long practiceId);

    @Select(
            """
        SELECT * FROM `lesson_practice_member`
        WHERE practice_id = #{practiceId}
        ORDER BY total_score DESC, solved_count DESC, penalty_time ASC, joined_at ASC
    """)
    List<LessonPracticeMember> listScoreboard(Long practiceId);

    @Select("SELECT COUNT(*) FROM `lesson_practice_member` WHERE practice_id = #{practiceId}")
    int countByPracticeId(Long practiceId);

    @Select(
            "SELECT COUNT(*) FROM `lesson_practice_member` WHERE practice_id = #{practiceId} AND submitted = 1")
    int countSubmittedByPracticeId(Long practiceId);

    @Select("SELECT practice_id FROM `lesson_practice_member` WHERE user_id = #{userId}")
    List<Long> listUserJoinedPracticeIds(Long userId);

    @Update(
            """
        UPDATE `lesson_practice_member`
        SET total_score = #{totalScore}, solved_count = #{solvedCount}
        WHERE practice_id = #{practiceId} AND user_id = #{userId}
    """)
    int updateScoreAndSolved(
            @Param("practiceId") Long practiceId,
            @Param("userId") Long userId,
            @Param("totalScore") int totalScore,
            @Param("solvedCount") int solvedCount);

    @Update(
            """
        UPDATE `lesson_practice_member`
        SET total_score = #{totalScore}, solved_count = #{solvedCount},
            penalty_time = #{penaltyTime}, last_active_at = #{lastActiveAt}
        WHERE practice_id = #{practiceId} AND user_id = #{userId}
    """)
    int updateScoreAndSolvedWithPenalty(
            @Param("practiceId") Long practiceId,
            @Param("userId") Long userId,
            @Param("totalScore") int totalScore,
            @Param("solvedCount") int solvedCount,
            @Param("penaltyTime") long penaltyTime,
            @Param("lastActiveAt") LocalDateTime lastActiveAt);

    @Update(
            """
        UPDATE `lesson_practice_member`
        SET submitted = 1, submitted_at = #{submittedAt}
        WHERE practice_id = #{practiceId} AND user_id = #{userId}
    """)
    int markSubmitted(
            @Param("practiceId") Long practiceId,
            @Param("userId") Long userId,
            @Param("submittedAt") LocalDateTime submittedAt);

    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM `lesson_practice_member` WHERE practice_id = #{practiceId}")
    int deleteByPracticeId(Long practiceId);
}
