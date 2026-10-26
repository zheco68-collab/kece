package com.kece.user.service;

import com.kece.user.entity.UserDailyAc;
import com.kece.user.mapper.UserDailyAcMapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 用户每日 AC 打卡数据聚合服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDailyAcService {

    private final UserDailyAcMapper userDailyAcMapper;

    /** 增量记录算法题 AC 打卡。 */
    public void recordAlgoAc(Long userId, LocalDate date) {
        if (userId == null || date == null) return;
        try {
            userDailyAcMapper.incrementAlgoAc(userId, date);
        } catch (Exception e) {
            log.error("增量记录算法题每日AC打卡失败 userId={}, date={}: {}", userId, date, e.getMessage());
        }
    }

    /** 增量记录数据库题 AC 打卡。 */
    public void recordDbAc(Long userId, LocalDate date) {
        if (userId == null || date == null) return;
        try {
            userDailyAcMapper.incrementDbAc(userId, date);
        } catch (Exception e) {
            log.error("增量记录数据库题每日AC打卡失败 userId={}, date={}: {}", userId, date, e.getMessage());
        }
    }

    /** 查询用户从某天开始的所有打卡记录（升序）。 */
    public List<UserDailyAc> findByUserAndStartDate(Long userId, LocalDate startDate) {
        if (userId == null || startDate == null) {
            return List.of();
        }
        return userDailyAcMapper.findByUserAndStartDate(userId, startDate);
    }
}
