package com.tjc.bugagent.config;

import com.tjc.bugagent.auth.AuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web infrastructure configuration.
 *
 * <p>没有 CORS 配置：生产走 nginx（/ 静态 + /api 反代同一 server），开发走 vite proxy，
 * 前后端始终同源。将来若真要跨域部署，再按显式白名单加回，别用 allowedOrigins("*")。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(120000);
        return new RestTemplate(factory);
    }

    /**
     * 除登录接口外全部要求已登录；管理员边界由拦截器内的路径前缀统一把关。
     * 错误页要放行，否则鉴权失败时转发 /error 会再被拦一次，把 401 盖成别的错。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/error");
    }
}
