package com.tjc.bugagent.auth;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户管理。整个 /users 前缀已被 AuthInterceptor 限定为管理员可访问，
 * 这里的 assertAdmin() 是第二道保险，防止将来 gate 配置被改漏。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final ProjectAccessGuard guard;

    public UserController(UserService userService, ProjectAccessGuard guard) {
        this.userService = userService;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<List<User>> listUsers() {
        guard.assertAdmin();
        return ApiResponse.ok(userService.listUsers());
    }

    @PostMapping
    public ApiResponse<User> createUser(@RequestBody Map<String, String> body) {
        guard.assertAdmin();
        return ApiResponse.ok(userService.createUser(
                body.get("username"), body.get("password"), body.get("role"), body.get("displayName")));
    }

    @PutMapping("/{userId}")
    public ApiResponse<String> updateUser(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        guard.assertAdmin();
        userService.updateUser(userId, body.get("role"), body.get("status"), body.get("displayName"));
        return ApiResponse.ok("ok");
    }

    @PostMapping("/{userId}/reset-password")
    public ApiResponse<String> resetPassword(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        guard.assertAdmin();
        userService.resetPassword(userId, body.get("password"));
        return ApiResponse.ok("ok");
    }
}
