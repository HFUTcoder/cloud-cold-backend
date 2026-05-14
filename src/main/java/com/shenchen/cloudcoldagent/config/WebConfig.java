package com.shenchen.cloudcoldagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * `WebConfig` 类型实现。
 */
@Configuration
public class WebConfig {

    @Bean
    @Qualifier("esObjectMapper")
    public ObjectMapper esObjectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    @Bean
    @Primary
    public ObjectMapper jacksonObjectMapper(@Qualifier("esObjectMapper") ObjectMapper esObjectMapper) {
        ObjectMapper objectMapper = esObjectMapper.copy();
        SimpleModule module = new SimpleModule();
        // 仅针对 Web 层：Long 转 String，防止前端精度丢失
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        objectMapper.registerModule(module);
        return objectMapper;
    }
}
