package com.tjc.bugagent.audit;

import com.tjc.bugagent.auth.CurrentUser;
import com.tjc.bugagent.auth.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 关键操作留痕。刻意做成 fire-and-forget：审计写失败绝不能连累业务，
 * 所以每个入口都吞异常，只把问题打进日志。
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL_LENGTH = 1024;

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 已登录用户的操作，操作人从登录上下文取。 */
    public void log(String action, String targetType, Object targetId, String detail) {
        CurrentUser user = UserContext.current();
        write(user == null ? null : user.getUserId(), user == null ? null : user.getUsername(),
                action, targetType, targetId, detail, true);
    }

    /** 登录成败：此时还没有登录上下文，操作人只能显式给出。 */
    public void logLogin(String username, boolean success, String detail) {
        write(null, username, success ? "LOGIN_SUCCESS" : "LOGIN_FAILURE", "USER", null, detail, success);
    }

    private void write(Long actorId, String actorName, String action, String targetType,
                       Object targetId, String detail, boolean success) {
        try {
            jdbcTemplate.update(
                    "insert into audit_log(actor_user_id, actor_username, action, target_type, target_id, detail, ip, success, created_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, now())",
                    actorId, actorName, action, targetType,
                    targetId == null ? null : String.valueOf(targetId), truncate(detail), clientIp(), success ? 1 : 0);
        } catch (Exception exception) {
            log.warn("审计日志写入失败 action={} target={}: {}", action, targetId, exception.getMessage());
        }
    }

    private String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH);
    }

    /** 取真实来源 IP：走了 nginx 反代，优先认 X-Forwarded-For 的首个地址。 */
    private String clientIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.trim().isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception exception) {
            return null;
        }
    }
}
