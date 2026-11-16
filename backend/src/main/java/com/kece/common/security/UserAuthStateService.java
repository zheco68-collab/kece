package com.kece.common.security;

import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 用户令牌版本号与账号启停状态校验服务（Redis 短期缓存 + DB 回源）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthStateService {

    private static final String KEY_PREFIX = "kece:user:auth_state:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    /** 校验用户是否处于启用状态且 JWT 中的 tokenVersion 与最新一致。 */
    public boolean isTokenValid(Long userId, int tokenVersion) {
        if (userId == null) {
            return false;
        }
        String cached = readCache(userId);
        if (cached != null) {
            int sep = cached.indexOf(':');
            if (sep > 0) {
                try {
                    int cachedTv = Integer.parseInt(cached.substring(0, sep));
                    int cachedStatus = Integer.parseInt(cached.substring(sep + 1));
                    return cachedStatus == 1 && cachedTv == tokenVersion;
                } catch (NumberFormatException ignored) {
                    // 缓存格式异常时回源数据库
                }
            }
        }

        User state = userMapper.findAuthStateById(userId);
        if (state == null) {
            return false;
        }
        int currentTv = state.getTokenVersion() != null ? state.getTokenVersion() : 0;
        int currentStatus = state.getStatus() != null ? state.getStatus() : 0;
        writeCache(userId, currentTv + ":" + currentStatus);
        return currentStatus == 1 && currentTv == tokenVersion;
    }

    /** 递增用户令牌版本号并清理认证状态缓存（含事务提交后二次清理）。 */
    public void incrementTokenVersionAndEvict(Long userId) {
        if (userId == null) {
            return;
        }
        userMapper.incrementTokenVersion(userId);
        evictCache(userId);
    }

    /** 清理指定用户的认证状态缓存；若当前处于事务中则在提交后再次清理防止并发回填旧值。 */
    public void evictCache(Long userId) {
        if (userId == null) {
            return;
        }
        deleteCacheKey(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteCacheKey(userId);
                        }
                    });
        }
    }

    private String readCache(Long userId) {
        if (redis == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(KEY_PREFIX + userId);
        } catch (DataAccessException e) {
            log.debug("读取用户认证状态缓存失败，回源 DB: userId={}", userId, e);
            return null;
        }
    }

    private void writeCache(Long userId, String value) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, value, CACHE_TTL);
        } catch (DataAccessException e) {
            log.debug("写入用户认证状态缓存失败: userId={}", userId, e);
        }
    }

    private void deleteCacheKey(Long userId) {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (DataAccessException e) {
            log.debug("删除用户认证状态缓存失败: userId={}", userId, e);
        }
    }
}
