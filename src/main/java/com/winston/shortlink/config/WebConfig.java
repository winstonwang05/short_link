package com.winston.shortlink.config;

import com.winston.shortlink.interceptor.AccessLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/**
 * @description: Spring MVC 的核心 Java 配置类
 * @author: Winston
 * @date: 2026/2/8 22:35
 * @version: 1.0
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer{


    private final AccessLogInterceptor accessLogInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLogInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/health", "/favicon.ico");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 完全清空，不添加任何静态资源处理器
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 完全清空，不添加任何视图控制器
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Spring Boot 3.5.3 路径匹配配置
        configurer.setUseTrailingSlashMatch(false);
        configurer.setUseSuffixPatternMatch(false);
    }

    @Override
    public void configureDefaultServletHandling(DefaultServletHandlerConfigurer configurer) {
        // 不启用默认 Servlet 处理
    }
}
