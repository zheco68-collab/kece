package com.kece.practice.mapper;

import com.kece.practice.dto.PracticeSummaryVO;
import com.kece.practice.entity.LessonPractice;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LessonPracticeMapper {

    @Insert(
            """
        INSERT INTO `lesson_practice` (title, description, access_code, duration_minutes, status, teacher_id, start_time, end_time)
        VALUES (#{title}, #{description}, #{accessCode}, #{durationMinutes}, #{status}, #{teacherId}, #{startTime}, #{endTime})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LessonPractice practice);

    @Select("SELECT * FROM `lesson_practice` WHERE id = #{id} LIMIT 1")
    LessonPractice findById(Long id);

    @Select("SELECT * FROM `lesson_practice` WHERE access_code = #{accessCode} LIMIT 1")
    LessonPractice findByAccessCode(String accessCode);

    @Select("SELECT * FROM `lesson_practice` ORDER BY id DESC LIMIT #{offset}, #{limit}")
    List<LessonPractice> listAll(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM `lesson_practice`")
    int countAll();

    @Select(
            """
        <script>
        SELECT * FROM `lesson_practice`
        <where>
            <if test="keyword != null and keyword != ''">
                (title LIKE CONCAT('%', #{keyword}, '%') OR access_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
                AND status = #{status}
            </if>
            <if test="teacherId != null">
                AND teacher_id = #{teacherId}
            </if>
        </where>
        ORDER BY id DESC
        LIMIT #{offset}, #{limit}
        </script>
    """)
    List<LessonPractice> listFilter(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("teacherId") Long teacherId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select(
            """
        <script>
        SELECT
            p.id,
            p.title,
            p.description,
            <choose>
                <when test="isAdminView or isCurrentAdmin">
                    p.access_code AS accessCode,
                </when>
                <when test="currentUserId != null">
                    CASE
                        WHEN p.teacher_id = #{currentUserId}
                             OR EXISTS (SELECT 1 FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId})
                        THEN p.access_code
                        ELSE NULL
                    END AS accessCode,
                </when>
                <otherwise>
                    NULL AS accessCode,
                </otherwise>
            </choose>
            p.duration_minutes AS durationMinutes,
            p.status,
            p.teacher_id AS teacherId,
            COALESCE(u.username, '教师') AS teacherName,
            p.start_time AS startTime,
            p.end_time AS endTime,
            p.created_at AS createdAt,
            (SELECT COUNT(1) FROM lesson_practice_member m WHERE m.practice_id = p.id) AS memberCount,
            (SELECT COUNT(1) FROM lesson_practice_question q WHERE q.practice_id = p.id) AS questionCount,
            (SELECT COUNT(1) FROM lesson_practice_question q WHERE q.practice_id = p.id AND q.question_type = 'OBJECTIVE') AS objectiveCount,
            (SELECT COUNT(1) FROM lesson_practice_question q WHERE q.practice_id = p.id AND q.question_type != 'OBJECTIVE') AS practicalCount,
            <choose>
                <when test="currentUserId != null">
                    (SELECT COUNT(1) FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId}) > 0 AS myJoined,
                    (SELECT total_score FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId} LIMIT 1) AS myScore,
                    COALESCE((SELECT submitted FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId} LIMIT 1), 0) = 1 AS mySubmitted
                </when>
                <otherwise>
                    FALSE AS myJoined, NULL AS myScore, FALSE AS mySubmitted
                </otherwise>
            </choose>
        FROM lesson_practice p
        LEFT JOIN `user` u ON p.teacher_id = u.id
        <where>
            <if test="keyword != null and keyword != ''">
                <choose>
                    <when test="isAdminView">
                        (p.title LIKE CONCAT('%', #{keyword}, '%')
                         OR COALESCE(p.description, '') LIKE CONCAT('%', #{keyword}, '%')
                         OR p.access_code LIKE CONCAT('%', #{keyword}, '%'))
                    </when>
                    <otherwise>
                        (p.title LIKE CONCAT('%', #{keyword}, '%')
                         OR COALESCE(p.description, '') LIKE CONCAT('%', #{keyword}, '%'))
                    </otherwise>
                </choose>
            </if>
            <if test="status != null and status != ''">
                AND p.status = #{status}
            </if>
            <if test="teacherId != null">
                AND p.teacher_id = #{teacherId}
            </if>
            <if test="joinedOnly">
                <choose>
                    <when test="currentUserId != null">
                        AND (
                            EXISTS (SELECT 1 FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId})
                            OR p.teacher_id = #{currentUserId}
                        )
                    </when>
                    <otherwise>
                        AND 1 = 0
                    </otherwise>
                </choose>
            </if>
        </where>
        ORDER BY p.id DESC
        LIMIT #{offset}, #{limit}
        </script>
    """)
    List<PracticeSummaryVO> listFilterAggregated(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("teacherId") Long teacherId,
            @Param("currentUserId") Long currentUserId,
            @Param("isAdminView") boolean isAdminView,
            @Param("isCurrentAdmin") boolean isCurrentAdmin,
            @Param("joinedOnly") boolean joinedOnly,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select(
            """
        <script>
        SELECT COUNT(*) FROM `lesson_practice` p
        <where>
            <if test="keyword != null and keyword != ''">
                <choose>
                    <when test="isAdminView">
                        (p.title LIKE CONCAT('%', #{keyword}, '%')
                         OR COALESCE(p.description, '') LIKE CONCAT('%', #{keyword}, '%')
                         OR p.access_code LIKE CONCAT('%', #{keyword}, '%'))
                    </when>
                    <otherwise>
                        (p.title LIKE CONCAT('%', #{keyword}, '%')
                         OR COALESCE(p.description, '') LIKE CONCAT('%', #{keyword}, '%'))
                    </otherwise>
                </choose>
            </if>
            <if test="status != null and status != ''">
                AND p.status = #{status}
            </if>
            <if test="teacherId != null">
                AND p.teacher_id = #{teacherId}
            </if>
            <if test="joinedOnly">
                <choose>
                    <when test="currentUserId != null">
                        AND (
                            EXISTS (SELECT 1 FROM lesson_practice_member m WHERE m.practice_id = p.id AND m.user_id = #{currentUserId})
                            OR p.teacher_id = #{currentUserId}
                        )
                    </when>
                    <otherwise>
                        AND 1 = 0
                    </otherwise>
                </choose>
            </if>
        </where>
        </script>
    """)
    int countFilter(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("teacherId") Long teacherId,
            @Param("currentUserId") Long currentUserId,
            @Param("isAdminView") boolean isAdminView,
            @Param("joinedOnly") boolean joinedOnly);

    @Select("SELECT * FROM `lesson_practice` WHERE teacher_id = #{teacherId} ORDER BY id DESC")
    List<LessonPractice> listByTeacherId(Long teacherId);

    /** 自动归档已到期的进行中课练（保留原计划 end_time 不变）。 */
    @Update(
            """
        UPDATE `lesson_practice`
        SET status = 'ENDED'
        WHERE status = 'ACTIVE' AND end_time IS NOT NULL AND end_time <= NOW()
    """)
    int closeExpiredPractices();

    @Update("UPDATE `lesson_practice` SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update(
            "UPDATE `lesson_practice` SET status = #{status}, end_time = #{endTime} WHERE id = #{id}")
    int updateStatusAndEndTime(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("endTime") LocalDateTime endTime);

    @Update(
            "UPDATE `lesson_practice` SET duration_minutes = #{durationMinutes}, end_time = #{endTime}, status = #{status} WHERE id = #{id}")
    int updateDurationAndStatus(
            @Param("id") Long id,
            @Param("durationMinutes") int durationMinutes,
            @Param("endTime") LocalDateTime endTime,
            @Param("status") String status);

    @Update(
            """
        UPDATE `lesson_practice`
        SET title = #{title}, description = #{description}, duration_minutes = #{durationMinutes}, end_time = #{endTime}, status = #{status}
        WHERE id = #{id}
    """)
    int updatePracticeConfig(
            @Param("id") Long id,
            @Param("title") String title,
            @Param("description") String description,
            @Param("durationMinutes") int durationMinutes,
            @Param("endTime") LocalDateTime endTime,
            @Param("status") String status);

    @Delete("DELETE FROM `lesson_practice` WHERE id = #{id}")
    int deleteById(Long id);
}
