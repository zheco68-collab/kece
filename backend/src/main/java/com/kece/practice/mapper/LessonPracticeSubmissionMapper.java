package com.kece.practice.mapper;

import com.kece.practice.entity.LessonPracticeSubmission;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LessonPracticeSubmissionMapper {

    @Insert(
            """
        INSERT INTO `lesson_practice_submission` (
            practice_id, practice_question_id, user_id, question_type, answer,
            submission_id, best_submission_id, status, score, attempt_count,
            result_json, created_at, best_achieved_at
        ) VALUES (
            #{practiceId}, #{practiceQuestionId}, #{userId}, #{questionType}, #{answer},
            #{submissionId}, #{bestSubmissionId}, #{status}, #{score}, #{attemptCount},
            #{resultJson}, #{createdAt}, #{bestAchievedAt}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LessonPracticeSubmission submission);

    @Select("SELECT * FROM `lesson_practice_submission` WHERE id = #{id} LIMIT 1")
    LessonPracticeSubmission findById(Long id);

    @Select(
            """
        SELECT * FROM `lesson_practice_submission`
        WHERE practice_id = #{practiceId} AND user_id = #{userId} AND practice_question_id = #{practiceQuestionId}
        ORDER BY id DESC LIMIT 1
    """)
    LessonPracticeSubmission findByPracticeUserQuestion(
            @Param("practiceId") Long practiceId,
            @Param("userId") Long userId,
            @Param("practiceQuestionId") Long practiceQuestionId);

    @Select(
            """
        SELECT * FROM `lesson_practice_submission`
        WHERE submission_id = #{submissionId} AND question_type = #{questionType}
        ORDER BY id DESC LIMIT 1
    """)
    LessonPracticeSubmission findBySubmissionIdAndType(
            @Param("submissionId") Long submissionId, @Param("questionType") String questionType);

    @Select(
            """
        SELECT * FROM `lesson_practice_submission`
        WHERE practice_id = #{practiceId} AND user_id = #{userId}
        ORDER BY id DESC
    """)
    List<LessonPracticeSubmission> listByPracticeAndUser(
            @Param("practiceId") Long practiceId, @Param("userId") Long userId);

    @Select(
            "SELECT * FROM `lesson_practice_submission` WHERE practice_question_id = #{practiceQuestionId}")
    List<LessonPracticeSubmission> listByPracticeQuestionId(Long practiceQuestionId);

    @Select(
            """
        SELECT * FROM `lesson_practice_submission`
        WHERE practice_id = #{practiceId}
        ORDER BY id DESC LIMIT #{limit}
    """)
    List<LessonPracticeSubmission> listRecentByPracticeId(
            @Param("practiceId") Long practiceId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM `lesson_practice_submission` WHERE practice_id = #{practiceId}")
    int countTotalByPracticeId(Long practiceId);

    @Select(
            "SELECT COALESCE(SUM(attempt_count), 0) FROM `lesson_practice_submission` WHERE practice_id = #{practiceId}")
    int sumAttemptsByPracticeId(Long practiceId);

    @Select(
            "SELECT COUNT(DISTINCT user_id) FROM `lesson_practice_submission` WHERE practice_question_id = #{practiceQuestionId} AND status = 'ACCEPTED'")
    int countPassByPracticeQuestionId(Long practiceQuestionId);

    @Select(
            "SELECT COUNT(DISTINCT user_id) FROM `lesson_practice_submission` WHERE practice_question_id = #{practiceQuestionId}")
    int countSubmitByPracticeQuestionId(Long practiceQuestionId);

    @Update(
            """
        UPDATE `lesson_practice_submission`
        SET answer = #{answer},
            submission_id = #{submissionId},
            best_submission_id = #{bestSubmissionId},
            status = #{status},
            score = #{score},
            attempt_count = #{attemptCount},
            result_json = #{resultJson},
            best_achieved_at = #{bestAchievedAt}
        WHERE id = #{id}
    """)
    int updateSubmission(LessonPracticeSubmission submission);

    @Delete(
            """
        <script>
        DELETE FROM `lesson_practice_submission`
        WHERE practice_question_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
    """)
    int deleteByPracticeQuestionIds(@Param("ids") List<Long> ids);

    @Delete("DELETE FROM `lesson_practice_submission` WHERE practice_id = #{practiceId}")
    int deleteByPracticeId(Long practiceId);
}
