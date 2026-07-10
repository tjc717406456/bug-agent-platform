package com.tjc.bugagent.auth;

/**
 * 未登录 / token 失效。由 GlobalExceptionHandler 映射为 HTTP 401，前端据此跳登录页。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
