package com.shenchen.cloudcoldagent.registry;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于文件系统的 Skill 注册表实现，委托给 spring-ai-alibaba 的 FileSystemSkillRegistry 处理项目技能，
 * 同时支持按用户目录扫描用户技能。用户技能的缓存按 userId 隔离，不同用户的同名 skill 互不冲突。
 */
@Slf4j
public class FileSystemSkillRegistry implements SkillRegistry {

    private final com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry delegate;
    private final Path userSkillsDir;
    private final Map<Long, Map<String, SkillMetadata>> userSkillCache = new ConcurrentHashMap<>();

    public FileSystemSkillRegistry(
            com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry delegate,
            Path userSkillsDir) {
        this.delegate = delegate;
        this.userSkillsDir = userSkillsDir;
    }

    @Override
    public List<SkillMetadata> listAll() {
        return delegate.listAll();
    }

    @Override
    public List<SkillMetadata> listUserSkills(Long userId) {
        if (userId == null || userId <= 0 || userSkillsDir == null) {
            return List.of();
        }
        Path userDir = userSkillsDir.resolve(String.valueOf(userId)).normalize();
        if (!Files.isDirectory(userDir)) {
            return List.of();
        }
        Map<String, SkillMetadata> userSkills = userSkillCache.computeIfAbsent(
                userId, k -> new ConcurrentHashMap<>());
        List<SkillMetadata> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(userDir)) {
            entries.filter(Files::isDirectory)
                    .forEach(dir -> {
                        String skillName = dir.getFileName().toString();
                        SkillMetadata metadata = SkillMetadata.builder()
                                .name(skillName)
                                .description("用户技能: " + skillName)
                                .source("USER")
                                .skillPath(dir.toAbsolutePath().toString())
                                .build();
                        result.add(metadata);
                        userSkills.put(skillName, metadata);
                    });
        } catch (IOException e) {
            log.warn("扫描用户技能目录失败。userId={}, path={}", userId, userDir, e);
        }
        return result;
    }

    @Override
    public Optional<SkillMetadata> get(String name) {
        Optional<SkillMetadata> result = delegate.get(name);
        if (result.isPresent()) {
            return result;
        }
        return userSkillCache.values().stream()
                .map(userSkills -> userSkills.get(name))
                .filter(metadata -> metadata != null)
                .findFirst();
    }

    @Override
    public boolean contains(String name) {
        if (delegate.contains(name)) {
            return true;
        }
        return userSkillCache.values().stream()
                .anyMatch(userSkills -> userSkills.containsKey(name));
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        if (delegate.contains(name)) {
            return delegate.readSkillContent(name);
        }
        SkillMetadata userSkill = userSkillCache.values().stream()
                .map(userSkills -> userSkills.get(name))
                .filter(metadata -> metadata != null)
                .findFirst()
                .orElse(null);
        if (userSkill != null) {
            Path skillFile = Path.of(userSkill.getSkillPath()).resolve("SKILL.md");
            if (Files.isRegularFile(skillFile)) {
                return Files.readString(skillFile, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("skill 内容不存在: " + name);
    }
}
