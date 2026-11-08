package com.kece.user.service;

import com.kece.judging.JudgeDispatch.Kind;
import com.kece.user.entity.UserDailyAc;
import com.kece.user.mapper.UserDailyAcMapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 用户每日 AC 打卡数据聚合服务。 */
@Service
@RequiredArgsConstructor
public class UserDailyAcService {

    private final UserDailyAcMapper userDailyAcMapper;

    /**
     * The caller holds the business submission lock. Rejudges retain the last confirmed
     * contribution.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncJudgeContribution(
            Kind kind, Long id, Long userId, LocalDate date, boolean accepted) {
        var previous = userDailyAcMapper.findContribution(kind.name(), id);
        if (accepted == (previous != null)) {
            return;
        }
        if (accepted) {
            userDailyAcMapper.addContribution(kind.name(), id, userId, date);
            if (kind == Kind.ALGO) {
                userDailyAcMapper.incrementAlgoAc(userId, date);
            } else {
                userDailyAcMapper.incrementDbAc(userId, date);
            }
        } else {
            userDailyAcMapper.removeContribution(kind.name(), id);
            int algo = kind == Kind.ALGO ? 1 : 0;
            if (userDailyAcMapper.decrementContribution(
                            previous.userId(), previous.acDate(), algo, 1 - algo)
                    != 1) {
                throw new IllegalStateException(
                        "Daily AC contribution cannot be reconciled: " + id);
            }
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
