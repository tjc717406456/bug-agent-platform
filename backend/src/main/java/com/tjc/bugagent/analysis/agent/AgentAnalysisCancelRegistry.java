package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Agent 分析任务的「手动停止」标记。
 * 先写本机内存（保证本实例正在跑的任务立即可见），再写 Redis（让停止请求打到别的实例也能停掉跨实例任务）。
 * Redis 不可用时仅靠内存，单机照常工作。
 */
@Component
public class AgentAnalysisCancelRegistry {
    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisCancelRegistry.class);
    private static final String KEY_PREFIX = "agent:analysis:cancel:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final Set<String> localStops = ConcurrentHashMap.newKeySet();

    public AgentAnalysisCancelRegistry(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
    }

    /** 请求停止某任务。 */
    public void requestStop(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        localStops.add(taskId);
        try {
            redisTemplate.opsForValue().set(key(taskId), "1", appProperties.getTaskTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            log.debug("写 Redis 取消标记失败，仅用内存标记，taskId={}", taskId, exception);
        }
    }

    /** 循环每轮查一次：本机标记或 Redis 标记任一命中即视为已停止。 */
    public boolean isStopRequested(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        if (localStops.contains(taskId)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(taskId)));
        } catch (Exception exception) {
            return false;
        }
    }

    /** 任务结束后清掉标记，避免脏数据残留。 */
    public void clear(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        localStops.remove(taskId);
        try {
            redisTemplate.delete(key(taskId));
        } catch (Exception exception) {
            log.debug("清 Redis 取消标记失败，taskId={}", taskId, exception);
        }
    }

    private String key(String taskId) {
        return KEY_PREFIX + taskId;
    }
}
