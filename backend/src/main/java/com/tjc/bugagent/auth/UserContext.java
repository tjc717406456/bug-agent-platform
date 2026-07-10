package com.tjc.bugagent.auth;

/**
 * 当前登录用户的线程上下文。AuthInterceptor 在 preHandle 里写入、afterCompletion 里清除。
 *
 * <p>注意：@Async 的分析线程读不到这里的值，这是刻意为之——所有归属校验都发生在
 * 提交任务的 servlet 线程里（见 AgentAnalysisTaskService），异步执行体只按已授权的
 * projectId / recordId 干活，不需要用户身份。切勿用 TaskDecorator 往线程池里传播它。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<CurrentUser>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前用户；未登录返回 null（受保护接口走不到这一步）。 */
    public static CurrentUser current() {
        return HOLDER.get();
    }

    /** 当前用户，缺失即抛 401——供 service 层在无法拿到请求上下文时兜底。 */
    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new UnauthorizedException("未登录");
        }
        return user;
    }

    public static Long currentUserId() {
        return require().getUserId();
    }

    public static boolean isAdmin() {
        CurrentUser user = HOLDER.get();
        return user != null && user.isAdmin();
    }
}
