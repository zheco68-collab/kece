package com.kece.user.service;

import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.entity.UserDailyAc;
import com.kece.user.mapper.UserDailyAcMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户每日 AC 打卡数据聚合服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDailyAcService {

    private final UserDailyAcMapper userDailyAcMapper;
    private final SubmissionMapper submissionMapper;
    private final DbSubmissionMapper dbSubmissionMapper;

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

    /**
     * 从原始提交明细表为指定用户全量重建打卡数据（运维或自愈用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rebuildForUser(Long userId) {
        if (userId == null) return;
        userDailyAcMapper.deleteByUserId(userId);

        LocalDate farPast = LocalDate.of(2000, 1, 1);
        List<Map<String, Object>> algoDaily = submissionMapper.countAcDaily(userId, farPast);
        List<Map<String, Object>> dbDaily = dbSubmissionMapper.countAcDaily(userId, farPast);

        Map<String, int[]> aggregated = new HashMap<>();
        if (algoDaily != null) {
            for (Map<String, Object> r : algoDaily) {
                String d = String.valueOf(r.get("date"));
                int c = ((Number) r.get("count")).intValue();
                aggregated.computeIfAbsent(d, k -> new int[2])[0] += c;
            }
        }
        if (dbDaily != null) {
            for (Map<String, Object> r : dbDaily) {
                String d = String.valueOf(r.get("date"));
                int c = ((Number) r.get("count")).intValue();
                aggregated.computeIfAbsent(d, k -> new int[2])[1] += c;
            }
        }

        for (Map.Entry<String, int[]> entry : aggregated.entrySet()) {
            LocalDate d = LocalDate.parse(entry.getKey());
            int algo = entry.getValue()[0];
            int db = entry.getValue()[1];
            userDailyAcMapper.upsert(
                    UserDailyAc.builder()
                            .userId(userId)
                            .acDate(d)
                            .algoCount(algo)
                            .dbCount(db)
                            .totalCount(algo + db)
                            .build());
        }
    }
}
