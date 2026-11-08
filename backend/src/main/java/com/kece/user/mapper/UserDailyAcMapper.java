package com.kece.user.mapper;

import com.kece.user.entity.UserDailyAc;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户每日 AC 活跃打卡 Mapper。 */
@Mapper
public interface UserDailyAcMapper {

    @Select(
            """
            SELECT user_id, ac_date FROM user_daily_judge_contribution
            WHERE submission_type = #{type} AND submission_id = #{id}
            """)
    Contribution findContribution(@Param("type") String type, @Param("id") Long id);

    @Insert(
            """
            INSERT INTO user_daily_judge_contribution (submission_type, submission_id, user_id, ac_date)
            VALUES (#{type}, #{id}, #{userId}, #{acDate})
            """)
    void addContribution(
            @Param("type") String type,
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("acDate") LocalDate acDate);

    @Delete(
            """
            DELETE FROM user_daily_judge_contribution
            WHERE submission_type = #{type} AND submission_id = #{id}
            """)
    void removeContribution(@Param("type") String type, @Param("id") Long id);

    @Update(
            """
            UPDATE user_daily_ac SET algo_count = algo_count - #{algo},
                db_count = db_count - #{db}, total_count = total_count - 1
            WHERE user_id = #{userId} AND ac_date = #{acDate}
              AND algo_count >= #{algo} AND db_count >= #{db} AND total_count > 0
            """)
    int decrementContribution(
            @Param("userId") Long userId,
            @Param("acDate") LocalDate acDate,
            @Param("algo") int algo,
            @Param("db") int db);

    record Contribution(Long userId, LocalDate acDate) {}

    /** 增量记录算法题 AC（若不存在则插入，存在则累加）。 */
    int incrementAlgoAc(@Param("userId") Long userId, @Param("acDate") LocalDate acDate);

    /** 增量记录数据库题 AC（若不存在则插入，存在则累加）。 */
    int incrementDbAc(@Param("userId") Long userId, @Param("acDate") LocalDate acDate);

    /** 查询用户从某天开始的所有打卡记录。 */
    List<UserDailyAc> findByUserAndStartDate(
            @Param("userId") Long userId, @Param("startDate") LocalDate startDate);

    /** 全量覆盖某天记录（用于数据修复或初始化）。 */
    int upsert(UserDailyAc record);

    /** 清理某用户的全部打卡数据（重建前调用）。 */
    int deleteByUserId(@Param("userId") Long userId);
}
