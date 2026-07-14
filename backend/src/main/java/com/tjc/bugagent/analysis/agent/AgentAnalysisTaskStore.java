package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 异步分析任务状态存储。
 * 优先落 Redis（多实例部署、重启不丢、TTL 自动清理）；
 * 当 Redis 不可用时自动降级到进程内存，保证单机仍能正常分析（功能不因 Redis 宕机而中断）。
 */
@Component
public class AgentAnalysisTaskStore {
    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisTaskStore.class);
    private static final String KEY_PREFIX = "agent:analysis:task:";
    private static final String PAYLOAD_KEY_PREFIX = "agent:analysis:payload:";
    private static final String HEARTBEAT_KEY_PREFIX = "agent:analysis:heartbeat:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    // Redis 不可用时的降级存储：仅在 Redis 操作失败时写入，Redis 健康时保持为空，避免内存泄漏
    private final Map<String, AgentAnalysisTaskStatus> fallback = new ConcurrentHashMap<String, AgentAnalysisTaskStatus>();
    private final Map<String, AgentAnalysisTaskPayload> payloadFallback = new ConcurrentHashMap<String, AgentAnalysisTaskPayload>();
    private final Map<String, Long> heartbeatFallback = new ConcurrentHashMap<String, Long>();
    // 只在首次降级时打印告警，避免每次轮询刷屏
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public AgentAnalysisTaskStore(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 写入任务状态并刷新 TTL；Redis 失败则写入内存降级存储。
     */
    public void save(AgentAnalysisTaskStatus status) {
        status.setHeartbeatAt(System.currentTimeMillis());
        try {
            String json = objectMapper.writeValueAsString(status);
            redisTemplate.opsForValue().set(key(status.getTaskId()), json,
                    appProperties.getTaskTtlSeconds(), TimeUnit.SECONDS);
            fallback.remove(status.getTaskId());
            touch(status.getTaskId());
            recovered();
        } catch (Exception exception) {
            warnDegraded("save", exception);
            fallback.put(status.getTaskId(), status);
        }
    }

    /**
     * 读取任务状态；Redis 失败或未命中时回退内存降级存储，都没有则返回 null。
     */
    public AgentAnalysisTaskStatus find(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(key(taskId));
            recovered();
            if (json != null) {
                return objectMapper.readValue(json, AgentAnalysisTaskStatus.class);
            }
            return fallback.get(taskId);
        } catch (Exception exception) {
            warnDegraded("read", exception);
            return fallback.get(taskId);
        }
    }

    /** 保存仅供恢复使用的任务输入，不通过状态查询接口返回。 */
    public void savePayload(String taskId, AgentAnalysisTaskPayload payload) {
        try {
            redisTemplate.opsForValue().set(PAYLOAD_KEY_PREFIX + taskId, objectMapper.writeValueAsString(payload),
                    appProperties.getTaskTtlSeconds(), TimeUnit.SECONDS);
            payloadFallback.remove(taskId);
            recovered();
        } catch (Exception exception) {
            warnDegraded("save-payload", exception);
            payloadFallback.put(taskId, payload);
        }
    }

    /** 读取任务恢复负载。 */
    public AgentAnalysisTaskPayload findPayload(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(PAYLOAD_KEY_PREFIX + taskId);
            recovered();
            return json == null ? payloadFallback.get(taskId)
                    : objectMapper.readValue(json, AgentAnalysisTaskPayload.class);
        } catch (Exception exception) {
            warnDegraded("read-payload", exception);
            return payloadFallback.get(taskId);
        }
    }

    /** 读取时把超过阈值且无心跳的运行任务改成中断，避免服务重启后永久显示 RUNNING。 */
    public AgentAnalysisTaskStatus findAndRepair(String taskId) {
        AgentAnalysisTaskStatus status = find(taskId);
        if (status == null || !"RUNNING".equals(status.getStatus())) {
            return status;
        }
        long staleMillis = Math.max(30, appProperties.getAgent().getTaskStaleSeconds()) * 1000L;
        long heartbeatAt = heartbeat(taskId, status.getHeartbeatAt());
        if (heartbeatAt > 0 && System.currentTimeMillis() - heartbeatAt <= staleMillis) {
            return status;
        }
        status.setStatus("INTERRUPTED");
        status.setStopReason(AgentStopReason.INTERRUPTED.name());
        status.setMessage("任务执行进程已中断，请重新提交");
        save(status);
        return status;
    }

    /** 只刷新独立心跳键，不序列化正在被执行线程修改的状态对象。 */
    public void touch(String taskId) {
        long now = System.currentTimeMillis();
        try {
            redisTemplate.opsForValue().set(HEARTBEAT_KEY_PREFIX + taskId, String.valueOf(now),
                    appProperties.getTaskTtlSeconds(), TimeUnit.SECONDS);
            heartbeatFallback.remove(taskId);
        } catch (Exception exception) {
            heartbeatFallback.put(taskId, now);
            warnDegraded("heartbeat", exception);
        }
    }

    private long heartbeat(String taskId, long statusHeartbeat) {
        try {
            String value = redisTemplate.opsForValue().get(HEARTBEAT_KEY_PREFIX + taskId);
            if (value != null) {
                return Long.parseLong(value);
            }
        } catch (Exception exception) {
            warnDegraded("read-heartbeat", exception);
        }
        Long fallbackHeartbeat = heartbeatFallback.get(taskId);
        return fallbackHeartbeat == null ? statusHeartbeat : fallbackHeartbeat;
    }

    private void warnDegraded(String op, Exception exception) {
        if (degraded.compareAndSet(false, true)) {
            log.warn("Redis 不可用，Agent 任务状态降级为进程内存（单机模式）。原因({}): {}", op, exception.getMessage());
        } else {
            log.debug("redis {} failed, using in-memory fallback", op, exception);
        }
    }

    private void recovered() {
        if (degraded.compareAndSet(true, false)) {
            log.info("Redis 已恢复，Agent 任务状态切回 Redis 存储。");
        }
    }

    private String key(String taskId) {
        return KEY_PREFIX + taskId;
    }
}
