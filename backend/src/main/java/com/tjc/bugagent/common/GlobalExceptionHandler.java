package com.tjc.bugagent.common;

import com.tjc.bugagent.auth.ForbiddenException;
import com.tjc.bugagent.auth.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts backend exceptions to stable API responses.
 *
 * <p>鉴权失败必须回真状态码：业务异常一律 200 + success=false，前端就没法把
 * “登录过期”和“这次操作失败”分开。401 触发前端跳登录页，403 只提示无权限、不登出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 未登录 / token 失效 → 401，前端据此清 token 并跳登录页。 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.<Void>fail(exception.getMessage()));
    }

    /** 已登录但越权 → 403，前端只提示，绝不能因此把用户踢下线。 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException exception) {
        log.warn("越权访问被拒: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.<Void>fail(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handle(Exception exception) {
        log.error("api failed", exception);
        return ApiResponse.fail(exception.getMessage());
    }
}
