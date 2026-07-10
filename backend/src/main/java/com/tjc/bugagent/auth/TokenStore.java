package com.tjc.bugagent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 登录 token 的 Redis 存储。选不透明 token 而非 JWT，是因为团队使用要求服务端能立刻撤销
 * （登出、禁用账号、改密踢下线）；JWT 也得配黑名单，不如直接把状态放 Redis。
 *
 * <p>刻意不做内存降级：Redis 不可用时应当拒绝登录（fail-closed），
 * 而不是退化成一个谁都能进的系统。
 */
@Component
public class TokenStore {

    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final String USER_TOKENS_KEY_PREFIX = "auth:user-tokens:";
    private static final String LOGIN_FAIL_KEY_PREFIX = "auth:loginfail:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 发放 token：256 位随机串，同时登记到用户的 token 集合，支持多端登录与一键全部踢下线。
     */
    public String issue(CurrentUser user) {
        String token = randomToken();
        long ttl = appProperties.getAuth().getTokenTtlSeconds();
        try {
            redisTemplate.opsForValue().set(tokenKey(token), objectMapper.writeValueAsString(user), ttl, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("签发登录凭证失败: " + exception.getMessage(), exception);
        }
        String userTokensKey = userTokensKey(user.getUserId());
        redisTemplate.opsForSet().add(userTokensKey, token);
        // 集合本身给足 TTL，避免用户长期不登录后残留空集合
        redisTemplate.expire(userTokensKey, ttl * 2, TimeUnit.SECONDS);
        return token;
    }

    /**
     * 解析 token，顺带滑动续期：剩余不足一半时续满，活跃用户不会被动登出。
     * token 不存在/已过期返回 null。
     */
    public CurrentUser resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String key = tokenKey(token);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        long ttl = appProperties.getAuth().getTokenTtlSeconds();
        Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (remaining != null && remaining > 0 && remaining < ttl / 2) {
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        }
        try {
            return objectMapper.readValue(json, CurrentUser.class);
        } catch (Exception exception) {
            // 存储格式异常等同于凭证无效，直接清掉
            redisTemplate.delete(key);
            return null;
        }
    }

    /** 单个 token 失效（登出）。 */
    public void revoke(String token, Long userId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        redisTemplate.delete(tokenKey(token));
        if (userId != null) {
            redisTemplate.opsForSet().remove(userTokensKey(userId), token);
        }
    }

    /** 该用户所有 token 失效：禁用账号、改密、重置密码时调用。 */
    public void revokeAll(Long userId) {
        String userTokensKey = userTokensKey(userId);
        Set<String> tokens = redisTemplate.opsForSet().members(userTokensKey);
        if (tokens != null) {
            for (String token : tokens) {
                redisTemplate.delete(tokenKey(token));
            }
        }
        redisTemplate.delete(userTokensKey);
    }

    /** 记一次登录失败，返回累计次数；到达阈值即进入锁定窗口。 */
    public long recordLoginFailure(String username) {
        String key = LOGIN_FAIL_KEY_PREFIX + username;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, appProperties.getAuth().getLoginLockSeconds(), TimeUnit.SECONDS);
        }
        return count == null ? 0 : count;
    }

    public boolean isLoginLocked(String username) {
        String value = redisTemplate.opsForValue().get(LOGIN_FAIL_KEY_PREFIX + username);
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(value) >= appProperties.getAuth().getMaxLoginFailures();
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public void clearLoginFailures(String username) {
        redisTemplate.delete(LOGIN_FAIL_KEY_PREFIX + username);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    private String userTokensKey(Long userId) {
        return USER_TOKENS_KEY_PREFIX + userId;
    }
}
