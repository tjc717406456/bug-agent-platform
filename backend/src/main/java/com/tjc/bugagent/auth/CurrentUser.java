package com.tjc.bugagent.auth;

/**
 * 当前请求的登录用户快照，由 AuthInterceptor 从 token 解出后放进 UserContext。
 */
public class CurrentUser {
    public static final String ROLE_ADMIN = "ADMIN";

    private Long userId;
    private String username;
    private String role;

    public CurrentUser() {
    }

    public CurrentUser(Long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
