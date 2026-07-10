package com.tjc.bugagent.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 鉴权相关 Bean。只用到 spring-security-crypto 的密码编码器，
 * 没有引入 Spring Security 的过滤器链与自动配置——鉴权由自写的 AuthInterceptor 承担。
 */
@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
