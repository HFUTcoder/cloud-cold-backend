package com.shenchen.cloudcoldagent.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SkillConfig {

    @Bean
    public SkillRegistry skillRegistry() {
        Path projectSkillsPath = Path.of("src/main/resources/skills").toAbsolutePath().normalize();
        if (!Files.isDirectory(projectSkillsPath)) {
            throw new IllegalStateException("skills 目录不存在: " + projectSkillsPath);
        }
        return FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(projectSkillsPath.toString())
                .autoLoad(true)
                .build();
    }
}
