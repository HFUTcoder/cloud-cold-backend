package com.shenchen.cloudcoldagent.registry;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 项目统一的 Skill 注册表接口，支持项目技能和用户技能的读取与发现。
 */
public interface SkillRegistry {

    List<SkillMetadata> listAll();

    List<SkillMetadata> listUserSkills(Long userId);

    Optional<SkillMetadata> get(String name);

    boolean contains(String name);

    String readSkillContent(String name) throws IOException;
}
