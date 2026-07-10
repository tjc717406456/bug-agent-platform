package com.tjc.bugagent.auth;

import com.tjc.bugagent.audit.AuditService;
import com.tjc.bugagent.auth.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录、登出、改密。密码只以 bcrypt 哈希落库，任何响应都不带出哈希。
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final AuditService auditService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, TokenStore tokenStore,
                       AuditService auditService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.auditService = auditService;
    }

    /**
     * 校验账号密码并签发 token。
     * 用户名不存在与密码错误返回同一句提示，避免被用来枚举账号；连续失败会触发锁定。
     */
    public LoginResponse login(String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            throw new UnauthorizedException("用户名或密码不能为空");
        }
        String name = username.trim();
        if (tokenStore.isLoginLocked(name)) {
            auditService.logLogin(name, false, "触发登录失败锁定");
            throw new UnauthorizedException("登录失败次数过多，请稍后再试");
        }
        User user = userMapper.findByUsername(name);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            long failures = tokenStore.recordLoginFailure(name);
            log.warn("登录失败 username={} 累计失败={}", name, failures);
            auditService.logLogin(name, false, "用户名或密码错误，累计失败 " + failures + " 次");
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (!user.isActive()) {
            auditService.logLogin(name, false, "账号已停用");
            throw new UnauthorizedException("账号已被停用，请联系管理员");
        }
        tokenStore.clearLoginFailures(name);
        userMapper.touchLastLogin(user.getId());

        CurrentUser currentUser = new CurrentUser(user.getId(), user.getUsername(), user.getRole());
        String token = tokenStore.issue(currentUser);
        log.info("登录成功 username={} role={}", user.getUsername(), user.getRole());
        auditService.logLogin(name, true, "role=" + user.getRole());

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setDisplayName(user.getDisplayName());
        response.setMustChangePassword(user.isMustChangePassword());
        return response;
    }

    public void logout(String token) {
        CurrentUser user = UserContext.current();
        auditService.log("LOGOUT", "USER", user == null ? null : user.getUserId(), null);
        tokenStore.revoke(token, user == null ? null : user.getUserId());
    }

    /**
     * 修改自己的密码。成功后踢掉该用户全部 token（含本次），强制各端重新登录。
     */
    public void changePassword(String oldPassword, String newPassword) {
        if (isBlank(newPassword) || newPassword.trim().length() < 8) {
            throw new IllegalArgumentException("新密码至少 8 位");
        }
        Long userId = UserContext.currentUserId();
        User user = userMapper.findById(userId);
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword.trim()), false);
        auditService.log("PASSWORD_CHANGE", "USER", userId, null);
        tokenStore.revokeAll(userId);
        log.info("用户改密并踢下线全部会话 username={}", user.getUsername());
    }

    /** 当前登录用户信息，供前端刷新后恢复身份与角色。 */
    public MeResponse me() {
        User user = userMapper.findById(UserContext.currentUserId());
        if (user == null) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        MeResponse response = new MeResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setDisplayName(user.getDisplayName());
        response.setMustChangePassword(user.isMustChangePassword());
        return response;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
