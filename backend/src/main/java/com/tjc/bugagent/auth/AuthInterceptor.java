package com.tjc.bugagent.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验 + 管理员边界。所有受保护接口的第一道闸。
 *
 * <p>管理员边界用「路径前缀」在此统一 gate，而不是散落在各 controller 的 assertAdmin()：
 * 单点可审计，新增 admin 接口不会漏配。controller 里的 assertAdmin() 只作后备。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 只有管理员能碰的路径前缀：AI 密钥、生产库凭据、跑批评测、用户管理 */
    private static final String[] ADMIN_PATH_PREFIXES = {
            "/ai-config", "/dbhub/datasources", "/eval", "/users"
    };
    private static final String BEARER_PREFIX = "Bearer ";
    /** 当前请求的原始 token，登出时要用它精确失效这一条 */
    private static final String TOKEN_ATTRIBUTE = "auth.token";

    private final TokenStore tokenStore;

    public AuthInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 预检请求不带凭证，放行交给 CORS 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        CurrentUser user = tokenStore.resolve(token);
        if (user == null) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (isAdminPath(path) && !user.isAdmin()) {
            throw new ForbiddenException("需要管理员权限");
        }
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 线程池会复用线程，不清会把身份带给下一个请求
        UserContext.clear();
    }

    /** 供登出接口取回当前 token。 */
    public static String currentToken(HttpServletRequest request) {
        Object token = request.getAttribute(TOKEN_ATTRIBUTE);
        return token == null ? null : String.valueOf(token);
    }

    private boolean isAdminPath(String path) {
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
