package com.hmdp.config;


import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/api/v1/stores/nearby",
                        "/api/v1/stores/*/medicines",
                        "/api/v1/stores/*/activities",
                        "/api/v1/activities/*",
                        // Spring's wildcard matcher may treat the root of the
                        // API-docs tree differently across matcher versions.
                        // The smoke stack probes this exact URL without a token.
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui",
                        "/swagger-ui/",
                        "/swagger-ui/**",
                        "/swagger-ui/index.html",
                        "/swagger-ui/swagger-initializer.js",
                        "/swagger-ui/swagger-ui.css",
                        "/swagger-ui/swagger-ui-bundle.js",
                        "/swagger-ui.html",
                        "/webjars/**"
                ).order(1);
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").order(0);
    }
}
