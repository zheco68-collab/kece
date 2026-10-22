package com.kece.user.mapper;

import com.kece.user.entity.UserDailyAc;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 用户每日 AC 活跃打卡 Mapper。 */
@Mapper
public interface UserDailyAcMapper {

    /** 增量记录算法题 AC（若不存在则插入，存在则累加）。 */
    int incrementAlgoAc(@Param("userId") Long userId, @Param("acDate") LocalDate acDate);

    /** 增量记录数据库题 AC（若不存在则插入，存在则累加）。 */
    int incrementDbAc(@Param("userId") Long userId, @Param("acDate") LocalDate acDate);

    /** 查询用户从某天开始的所有打卡记录。 */
    List<UserDailyAc> findByUserAndStartDate(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate);

    /** 全量覆盖某天记录（用于数据修复或初始化）。 */
    int upsert(UserDailyAc record);

    /** 清理某用户的全部打卡数据（重建前调用）。 */
    int deleteByUserId(@Param("userId") Long userId);
}
