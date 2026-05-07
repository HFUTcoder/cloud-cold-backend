package com.shenchen.cloudcoldagent.registry;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册表缓存装饰器，对 skill 内容和元数据列表进行内存缓存。
 */
@Slf4j
public class CachingSkillRegistry implements SkillRegistry {

    private final SkillRegistry delegate;
    private final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
    private volatile List<SkillMetadata> metadataCache;

    public CachingSkillRegistry(SkillRegistry delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<SkillMetadata> listAll() {
        if (metadataCache == null) {
            synchronized (this) {
                if (metadataCache == null) {
                    metadataCache = delegate.listAll();
                }
            }
        }
        return metadataCache;
    }

    @Override
    public List<SkillMetadata> listUserSkills(Long userId) {
        return delegate.listUserSkills(userId);
    }

    @Override
    public Optional<SkillMetadata> get(String name) {
        return delegate.get(name);
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        try {
            return contentCache.computeIfAbsent(name, key -> {
                try {
                    return delegate.readSkillContent(key);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause() instanceof IOException ioException ? ioException : new IOException(e.getCause());
        }
    }
}
