package com.tjc.bugagent.auth;

/**
 * 已登录但无权访问该资源。映射为 HTTP 403——注意与 401 区分：
 * 403 不该让前端登出，只是这次操作不允许。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
