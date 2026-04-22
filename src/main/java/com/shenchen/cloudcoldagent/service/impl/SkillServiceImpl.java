package com.shenchen.cloudcoldagent.service.impl;

import com.alibaba.cloud.ai.agent.python.tool.PythonTool;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.vo.SkillMetadataVO;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceContentVO;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.model.vo.SkillScriptExecutionVO;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.utils.PythonScriptRuntimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
public class SkillServiceImpl implements SkillService {

    private static final String RESOURCE_TYPE_MAIN = "main";
    private static final String RESOURCE_TYPE_REFERENCE = "reference";
    private static final String RESOURCE_TYPE_SCRIPT = "script";
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:\\\\.*");

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private PythonTool pythonTool;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<SkillMetadataVO> listSkillMetadata() {
        return skillRegistry.listAll().stream()
                .map(this::toSkillMetadataVO)
                .toList();
    }

    @Override
    public SkillMetadataVO getSkillMetadata(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "skillName 不能为空");
        }
        Optional<SkillMetadata> metadataOptional = skillRegistry.get(skillName.trim());
        SkillMetadata metadata = metadataOptional.orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 不存在")
        );
        return toSkillMetadataVO(metadata);
    }

    @Override
    public String readSkillContent(String skillName) throws IOException {
        return skillRegistry.readSkillContent(skillName);
    }

    @Override
    public SkillResourceContentVO readSkillResource(String skillName, String resourceType, String resourcePath,
                                                    Integer startLine, Integer endLine) throws IOException {
        SkillMetadata metadata = getSkillMetadataInternal(skillName);
        String normalizedType = normalizeResourceType(resourceType);

        if (RESOURCE_TYPE_MAIN.equals(normalizedType)) {
            String content = skillRegistry.readSkillContent(metadata.getName());
            return buildContentResult(metadata.getName(), normalizedType, null, content, startLine, endLine);
        }

        String normalizedResourcePath = normalizeResourcePath(resourcePath, normalizedType);
        Path skillBasePath = resolveSkillBasePath(metadata);
        Path resolvedPath = resolveResourcePath(skillBasePath, normalizedType, normalizedResourcePath);

        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 资源不存在: " + normalizedResourcePath);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        return buildContentResult(metadata.getName(), normalizedType, normalizedResourcePath, content, startLine, endLine);
    }

    @Override
    public SkillResourceListVO listSkillResources(String skillName) throws IOException {
        SkillMetadata metadata = getSkillMetadataInternal(skillName);
        Path skillBasePath = resolveSkillBasePath(metadata);

        return SkillResourceListVO.builder()
                .skillName(metadata.getName())
                .mainFile("SKILL.md")
                .references(listFilesUnder(skillBasePath, "references"))
                .scripts(listFilesUnder(skillBasePath, "scripts"))
                .build();
    }

    @Override
    public SkillScriptExecutionVO executeSkillScript(String skillName, String scriptPath, Map<String, Object> arguments)
            throws IOException {
        SkillMetadata metadata = getSkillMetadataInternal(skillName);
        String normalizedScriptPath = normalizeResourcePath(scriptPath, RESOURCE_TYPE_SCRIPT);
        Path skillBasePath = resolveSkillBasePath(metadata);
        Path resolvedScriptPath = resolveResourcePath(skillBasePath, RESOURCE_TYPE_SCRIPT, normalizedScriptPath);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;

        log.info("开始执行 skill script，skillName={}, scriptPath={}, arguments={}",
                metadata.getName(), normalizedScriptPath, safeArguments);

        if (!Files.exists(resolvedScriptPath) || !Files.isRegularFile(resolvedScriptPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "script 资源不存在: " + normalizedScriptPath);
        }

        String scriptContent = Files.readString(resolvedScriptPath, StandardCharsets.UTF_8);
        log.info("已加载 skill script，skillName={}, scriptPath={}, absolutePath={}, scriptLength={}",
                metadata.getName(), normalizedScriptPath, resolvedScriptPath, scriptContent.length());
        String wrappedCode = PythonScriptRuntimeUtils.buildWrappedCode(objectMapper, arguments, scriptContent);
        log.info("准备调用 PythonTool 执行脚本，skillName={}, scriptPath={}, wrappedCodeLength={}",
                metadata.getName(), normalizedScriptPath, wrappedCode.length());
        String result = pythonTool.apply(new PythonTool.PythonRequest(wrappedCode), null);
        log.info("skill script 执行完成，skillName={}, scriptPath={}, result={}",
                metadata.getName(), normalizedScriptPath, truncateForLog(result));

        return SkillScriptExecutionVO.builder()
                .skillName(metadata.getName())
                .scriptPath(normalizedScriptPath)
                .arguments(safeArguments)
                .result(result)
                .engine("spring-ai-alibaba-python-tool")
                .build();
    }

    private SkillMetadataVO toSkillMetadataVO(SkillMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return SkillMetadataVO.builder()
                .name(metadata.getName())
                .description(metadata.getDescription())
                .source(metadata.getSource())
                .build();
    }

    private SkillMetadata getSkillMetadataInternal(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "skillName 不能为空");
        }
        Optional<SkillMetadata> metadataOptional = skillRegistry.get(skillName.trim());
        return metadataOptional.orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 不存在")
        );
    }

    private String normalizeResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourceType 不能为空");
        }
        String normalizedType = resourceType.trim().toLowerCase();
        if (!RESOURCE_TYPE_MAIN.equals(normalizedType)
                && !RESOURCE_TYPE_REFERENCE.equals(normalizedType)
                && !RESOURCE_TYPE_SCRIPT.equals(normalizedType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourceType 仅支持 main/reference/script");
        }
        return normalizedType;
    }

    private String normalizeResourcePath(String resourcePath, String resourceType) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourcePath 不能为空");
        }
        String normalizedPath = resourcePath.trim().replace("\\", "/");
        if (normalizedPath.startsWith("/") || normalizedPath.startsWith("~") || WINDOWS_ABSOLUTE_PATH.matcher(resourcePath.trim()).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourcePath 不允许使用绝对路径");
        }
        Path resourceRelativePath = Path.of(normalizedPath).normalize();
        if (resourceRelativePath.isAbsolute()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourcePath 不允许使用绝对路径");
        }
        if (resourceRelativePath.startsWith("..")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourcePath 不允许路径穿越");
        }
        if (RESOURCE_TYPE_REFERENCE.equals(resourceType) && !normalizedPath.startsWith("references/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "reference 资源必须位于 references 目录下");
        }
        if (RESOURCE_TYPE_SCRIPT.equals(resourceType) && !normalizedPath.startsWith("scripts/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "script 资源必须位于 scripts 目录下");
        }
        return normalizedPath;
    }

    private String truncateForLog(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500) + "...(truncated)";
    }

    private Path resolveSkillBasePath(SkillMetadata metadata) {
        String skillPath = metadata.getSkillPath();
        if (skillPath == null || skillPath.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "skillPath 不存在，暂时无法读取资源文件");
        }
        Path basePath = Path.of(skillPath).normalize();
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 资源目录不存在");
        }
        return basePath;
    }

    private Path resolveResourcePath(Path skillBasePath, String resourceType, String normalizedResourcePath) {
        Path resolvedPath = skillBasePath.resolve(normalizedResourcePath).normalize();
        if (!resolvedPath.startsWith(skillBasePath)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resourcePath 不允许越过 skill 根目录");
        }
        if (RESOURCE_TYPE_REFERENCE.equals(resourceType) && !resolvedPath.startsWith(skillBasePath.resolve("references").normalize())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "reference 资源路径非法");
        }
        if (RESOURCE_TYPE_SCRIPT.equals(resourceType) && !resolvedPath.startsWith(skillBasePath.resolve("scripts").normalize())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "script 资源路径非法");
        }
        return resolvedPath;
    }

    private List<String> listFilesUnder(Path skillBasePath, String directoryName) throws IOException {
        Path targetDirectory = skillBasePath.resolve(directoryName).normalize();
        if (!targetDirectory.startsWith(skillBasePath) || !Files.exists(targetDirectory) || !Files.isDirectory(targetDirectory)) {
            return new ArrayList<>();
        }

        try (Stream<Path> pathStream = Files.walk(targetDirectory)) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .map(path -> skillBasePath.relativize(path).toString().replace("\\", "/"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private SkillResourceContentVO buildContentResult(String skillName, String resourceType, String resourcePath,
                                                      String content, Integer startLine, Integer endLine) {
        String safeContent = content == null ? "" : content;
        String[] lines = safeContent.split("\\R", -1);
        int totalLines = lines.length == 0 ? 1 : lines.length;
        int safeStart = normalizeStartLine(startLine, totalLines);
        int safeEnd = normalizeEndLine(endLine, safeStart, totalLines);
        boolean truncated = safeStart != 1 || safeEnd != totalLines;

        String slicedContent = joinLines(lines, safeStart, safeEnd);

        return SkillResourceContentVO.builder()
                .skillName(skillName)
                .resourceType(resourceType)
                .resourcePath(resourcePath)
                .startLine(safeStart)
                .endLine(safeEnd)
                .truncated(truncated)
                .content(slicedContent)
                .build();
    }

    private int normalizeStartLine(Integer startLine, int totalLines) {
        if (startLine == null || startLine <= 0) {
            return 1;
        }
        return Math.min(startLine, totalLines);
    }

    private int normalizeEndLine(Integer endLine, int startLine, int totalLines) {
        if (endLine == null || endLine <= 0) {
            return totalLines;
        }
        int safeEnd = Math.min(endLine, totalLines);
        if (safeEnd < startLine) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "endLine 不能小于 startLine");
        }
        return safeEnd;
    }

    private String joinLines(String[] lines, int startLine, int endLine) {
        if (lines.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < endLine; i++) {
            sb.append(lines[i]);
            if (i < endLine - 1) {
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
