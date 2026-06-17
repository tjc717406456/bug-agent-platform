package com.tjc.bugagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts backend exceptions to stable API responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handle(Exception exception) {
        log.error("api failed", exception);
        return ApiResponse.fail(exception.getMessage());
    }
}

