package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.registry.CachingSkillRegistry;
import com.shenchen.cloudcoldagent.registry.FileSystemSkillRegistry;
import com.shenchen.cloudcoldagent.registry.SkillRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Skill 注册表配置，创建带缓存的项目级 SkillRegistry。
 */
@Configuration
public class SkillConfig {

    @Value("${skills.user-skills-dir:}")
    private String userSkillsDir;

    /**
     * 创建带缓存装饰的 Skill 注册表。
     *
     * @return SkillRegistry 实例。
     */
    @Bean
    public SkillRegistry skillRegistry() {
        Path projectSkillsPath = Path.of("src/main/resources/skills").toAbsolutePath().normalize();
        if (!Files.isDirectory(projectSkillsPath)) {
            throw new IllegalStateException("skills 目录不存在: " + projectSkillsPath);
        }

        com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry delegate =
                com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.builder()
                        .projectSkillsDirectory(projectSkillsPath.toString())
                        .autoLoad(true)
                        .build();

        Path userSkillsPath = (userSkillsDir != null && !userSkillsDir.isBlank())
                ? Path.of(userSkillsDir).toAbsolutePath().normalize()
                : null;

        FileSystemSkillRegistry fileSystemRegistry = new FileSystemSkillRegistry(delegate, userSkillsPath);
        return new CachingSkillRegistry(fileSystemRegistry);
    }
}
