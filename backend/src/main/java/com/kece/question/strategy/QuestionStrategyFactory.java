package com.kece.question.strategy;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 题目类型策略工厂：自动扫描并管理所有题型策略实现类，提供依据 typeCode 的动态多态查找能力。 */
@Component
public class QuestionStrategyFactory {

    private final Map<String, QuestionTypeStrategy> strategyMap = new ConcurrentHashMap<>();

    public QuestionStrategyFactory(Collection<QuestionTypeStrategy> strategies) {
        if (strategies != null) {
            for (QuestionTypeStrategy strategy : strategies) {
                strategyMap.put(strategy.getTypeCode().trim().toUpperCase(), strategy);
            }
        }
        if (strategyMap.containsKey("DB")) {
            strategyMap.putIfAbsent("DATABASE", strategyMap.get("DB"));
        }
    }

    /**
     * 根据题型代码获取对应策略，未匹配时抛出业务异常。
     *
     * @param questionType 题型代码（大小写不敏感，如 "algo", "ALGO", "db", "objective", "frontend"）
     * @return 对应的题型策略
     */
    public QuestionTypeStrategy getStrategy(String questionType) {
        if (questionType == null || questionType.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题型不能为空");
        }
        QuestionTypeStrategy strategy = strategyMap.get(questionType.trim().toUpperCase());
        if (strategy == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的题型: " + questionType);
        }
        return strategy;
    }

    /** 安全查找策略，不存在时返回空 Optional。 */
    public Optional<QuestionTypeStrategy> findStrategy(String questionType) {
        if (questionType == null || questionType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(strategyMap.get(questionType.trim().toUpperCase()));
    }

    /** 检查系统是否已接入支持某题型。 */
    public boolean supports(String questionType) {
        if (questionType == null || questionType.isBlank()) {
            return false;
        }
        return strategyMap.containsKey(questionType.trim().toUpperCase());
    }

    /** 获取当前系统注册的所有题型策略列表（去重，排除别名如 DATABASE）。 */
    public Collection<QuestionTypeStrategy> getAllStrategies() {
        return strategyMap.values().stream().distinct().toList();
    }
}
