package com.shenchen.cloudcoldagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 适配Spring MVC的跨域配置类
 * 同时处理favicon.ico静态资源映射，避免报错
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // 跨域核心配置（适配Spring MVC）
    /**
     * 处理 `add Cors Mappings` 对应逻辑。
     *
     * @param registry registry 参数。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 对所有路径生效
                .allowedOriginPatterns("*") // 允许所有来源（生产环境建议指定具体域名）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的HTTP方法
                .allowedHeaders("*") // 允许所有请求头
                .allowCredentials(true) // 允许携带认证信息（如cookies）
                .maxAge(3600); // 预检请求缓存时间（秒）
    }

    // 配置静态资源，解决favicon.ico报错
    /**
     * 处理 `add Resource Handlers` 对应逻辑。
     *
     * @param registry registry 参数。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 1. 优先映射favicon.ico，即使文件不存在也不会抛出ERROR级异常
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0); // 禁用缓存，方便测试

        // 2. 配置默认静态资源（Spring MVC默认也会处理，但显式配置更清晰）
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/", "classpath:/public/");
    }
}
