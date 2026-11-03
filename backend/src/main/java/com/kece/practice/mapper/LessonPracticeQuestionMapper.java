package com.kece.practice.mapper;

import com.kece.practice.entity.LessonPracticeQuestion;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LessonPracticeQuestionMapper {

    @Insert(
            """
        <script>
        INSERT INTO `lesson_practice_question` (practice_id, question_id, question_type, title, score, sort_order)
        VALUES
        <foreach collection="list" item="item" separator=",">
            (#{item.practiceId}, #{item.questionId}, #{item.questionType}, #{item.title}, #{item.score}, #{item.sortOrder})
        </foreach>
        </script>
    """)
    int insertBatch(@Param("list") List<LessonPracticeQuestion> list);

    @Insert(
            """
        INSERT INTO `lesson_practice_question` (practice_id, question_id, question_type, title, score, sort_order)
        VALUES (#{practiceId}, #{questionId}, #{questionType}, #{title}, #{score}, #{sortOrder})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOne(LessonPracticeQuestion item);

    @Update(
            """
        UPDATE `lesson_practice_question`
        SET title = #{title}, score = #{score}, sort_order = #{sortOrder}
        WHERE id = #{id}
    """)
    int updateQuestionItem(LessonPracticeQuestion item);

    @Select(
            "SELECT * FROM `lesson_practice_question` WHERE practice_id = #{practiceId} ORDER BY sort_order ASC, id ASC")
    List<LessonPracticeQuestion> listByPracticeId(Long practiceId);

    @Select("SELECT * FROM `lesson_practice_question` WHERE id = #{id} LIMIT 1")
    LessonPracticeQuestion findById(Long id);

    @Select(
            """
        SELECT COUNT(1) > 0 FROM `lesson_practice_question`
        WHERE practice_id = #{practiceId} AND question_type = #{questionType} AND question_id = #{questionId}
    """)
    boolean existsPracticeQuestion(
            @Param("practiceId") Long practiceId,
            @Param("questionType") String questionType,
            @Param("questionId") Long questionId);

    @Delete(
            """
        <script>
        DELETE FROM `lesson_practice_question`
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
    """)
    int deleteByIds(@Param("ids") List<Long> ids);

    @Delete("DELETE FROM `lesson_practice_question` WHERE practice_id = #{practiceId}")
    int deleteByPracticeId(Long practiceId);
}
