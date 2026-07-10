package com.tjc.bugagent.auth;

import com.tjc.bugagent.audit.AuditService;
import com.tjc.bugagent.auth.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户管理（管理员专用）。没有开放注册，账号一律由管理员创建。
 */
@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final AuditService auditService;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, TokenStore tokenStore,
                       AuditService auditService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.auditService = auditService;
    }

    /** 列表不含密码哈希（见 UserMapper.xml 的 listAll）。 */
    public List<User> listUsers() {
        return userMapper.listAll();
    }

    public User createUser(String username, String password, String role, String displayName) {
        if (isBlank(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        validatePassword(password);
        String name = username.trim();
        if (userMapper.findByUsername(name) != null) {
            throw new IllegalArgumentException("用户名已存在: " + name);
        }
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password.trim()));
        user.setRole(normalizeRole(role));
        user.setStatus(User.STATUS_ACTIVE);
        user.setDisplayName(isBlank(displayName) ? name : displayName.trim());
        // 不强制改密：用户可随时通过「修改密码」自行更换
        user.setMustChangePassword(false);
        userMapper.insert(user);
        log.info("管理员创建用户 username={} role={}", user.getUsername(), user.getRole());
        auditService.log("USER_CREATE", "USER", user.getId(), user.getUsername() + " role=" + user.getRole());
        user.setPasswordHash(null);
        return user;
    }

    /**
     * 改角色/状态/显示名。禁用或降级最后一个可用管理员会被拒绝，避免把自己锁在系统外。
     */
    public void updateUser(Long userId, String role, String status, String displayName) {
        User user = requireUser(userId);
        String newRole = normalizeRole(role);
        String newStatus = normalizeStatus(status);
        boolean losingAdmin = CurrentUser.ROLE_ADMIN.equals(user.getRole())
                && (!CurrentUser.ROLE_ADMIN.equals(newRole) || !User.STATUS_ACTIVE.equals(newStatus));
        if (losingAdmin && userMapper.countActiveAdminsExcept(userId) == 0) {
            throw new IllegalArgumentException("必须保留至少一个启用状态的管理员");
        }
        userMapper.updateProfile(userId, newRole, newStatus, isBlank(displayName) ? user.getUsername() : displayName.trim());
        // 被停用或被降级的用户，已签发的 token 立即作废，不等自然过期
        if (!User.STATUS_ACTIVE.equals(newStatus) || !newRole.equals(user.getRole())) {
            tokenStore.revokeAll(userId);
        }
        auditService.log("USER_UPDATE", "USER", userId, "role=" + newRole + " status=" + newStatus);
        log.info("管理员更新用户 userId={} role={} status={}", userId, newRole, newStatus);
    }

    /**
     * 管理员重置他人密码：改完把对方踢下线，让其用新密码重新登录。不强制其再次修改。
     * 改自己的密码请走 /auth/change-password，别用重置——那会把自己踢下线。
     */
    public void resetPassword(Long userId, String newPassword) {
        validatePassword(newPassword);
        User user = requireUser(userId);
        if (userId.equals(UserContext.currentUserId())) {
            throw new IllegalArgumentException("修改自己的密码请使用「修改密码」功能，重置会把自己踢下线");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword.trim()), false);
        tokenStore.revokeAll(userId);
        auditService.log("USER_RESET_PASSWORD", "USER", userId, user.getUsername());
        log.info("管理员重置用户密码 username={}", user.getUsername());
    }

    private User requireUser(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        return user;
    }

    private void validatePassword(String password) {
        if (password == null || password.trim().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private String normalizeRole(String role) {
        return CurrentUser.ROLE_ADMIN.equalsIgnoreCase(role) ? CurrentUser.ROLE_ADMIN : "USER";
    }

    private String normalizeStatus(String status) {
        return User.STATUS_DISABLED.equalsIgnoreCase(status) ? User.STATUS_DISABLED : User.STATUS_ACTIVE;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
