package com.tjc.bugagent.auth;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 登录相关接口。只有 /auth/login 免鉴权（见 WebConfig 白名单），其余都要带 token。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.login(body.get("username"), body.get("password")));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request) {
        authService.logout(AuthInterceptor.currentToken(request));
        return ApiResponse.ok("ok");
    }

    /** 前端刷新页面后靠它恢复身份与角色。 */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me());
    }

    /** 改自己的密码；成功后所有端的 token 失效，需重新登录。 */
    @PostMapping("/change-password")
    public ApiResponse<String> changePassword(@RequestBody Map<String, String> body) {
        authService.changePassword(body.get("oldPassword"), body.get("newPassword"));
        return ApiResponse.ok("ok");
    }
}
