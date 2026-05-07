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
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillArgumentSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 服务实现，负责 skill 元数据读取、资源读取、参数解析和脚本执行。
 */
@Service
@Slf4j
public class SkillServiceImpl implements SkillService {

    private static final String RESOURCE_TYPE_MAIN = "main";
    private static final String RESOURCE_TYPE_REFERENCE = "reference";
    private static final String RESOURCE_TYPE_SCRIPT = "script";
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:\\\\.*");
    private static final Pattern SCRIPT_PATH_LINE = Pattern.compile("^-\\s*path:\\s*`([^`]+)`\\s*$");
    private static final Pattern ARGUMENT_LINE = Pattern.compile(
            "^-\\s*`([^`]+)`\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(required|optional)(?:\\s*\\|\\s*default=([^|]+?))?(?:\\s*\\|\\s*(.+))?$"
    );

    private final SkillRegistry skillRegistry;

    private final PythonTool pythonTool;

    private final ObjectMapper objectMapper;

    /**
     * 注入 skill 读取与脚本执行所需的依赖。
     *
     * @param skillRegistry skill 注册表。
     * @param pythonTool Python 脚本执行工具。
     * @param objectMapper JSON 映射器。
     */
    public SkillServiceImpl(SkillRegistry skillRegistry,
                            PythonTool pythonTool,
                            ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.pythonTool = pythonTool;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询当前系统已注册的全部 skill 元数据。
     *
     * @return skill 元数据列表。
     */
    @Override
    public List<SkillMetadataVO> listSkillMetadata() {
        return skillRegistry.listAll().stream()
                .map(this::toSkillMetadataVO)
                .toList();
    }

    /**
     * 查询指定 skill 的元数据。
     *
     * @param skillName skill 名称。
     * @return skill 元数据视图。
     */
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

    /**
     * 读取 skill 主内容，通常是 `SKILL.md`。
     *
     * @param skillName skill 名称。
     * @return skill 主内容文本。
     * @throws IOException 读取 skill 内容失败时抛出。
     */
    @Override
    public String readSkillContent(String skillName) throws IOException {
        return skillRegistry.readSkillContent(skillName);
    }

    /**
     * 读取指定 skill 的附属资源内容。
     *
     * @param skillName skill 名称。
     * @param resourceType 资源类型。
     * @param resourcePath 资源相对路径。
     * @param startLine 起始行号。
     * @param endLine 结束行号。
     * @return skill 资源内容。
     * @throws IOException 读取资源失败时抛出。
     */
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

    /**
     * 列出某个 skill 下的主文件、references 和 scripts 资源清单。
     *
     * @param skillName skill 名称。
     * @return skill 资源清单。
     * @throws IOException 读取 skill 资源失败时抛出。
     */
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

    /**
     * 执行某个 skill 下的脚本入口，并返回结构化执行结果。
     *
     * @param skillName skill 名称。
     * @param scriptPath 脚本相对路径。
     * @param arguments 脚本参数。
     * @return skill 脚本执行结果。
     * @throws IOException 读取脚本失败时抛出。
     */
    @Override
    public SkillScriptExecutionVO executeSkillScript(String skillName, String scriptPath, Map<String, Object> arguments)
            throws IOException {
        SkillMetadata metadata = getSkillMetadataInternal(skillName);
        String normalizedScriptPath = normalizeResourcePath(scriptPath, RESOURCE_TYPE_SCRIPT);
        Path skillBasePath = resolveSkillBasePath(metadata);
        Path resolvedScriptPath = resolveResourcePath(skillBasePath, RESOURCE_TYPE_SCRIPT, normalizedScriptPath);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        Map<String, SkillArgumentSpec> argumentSpecs = parseArgumentSpecsFromSkillContent(
                skillRegistry.readSkillContent(metadata.getName()),
                normalizedScriptPath
        );
        long startNanos = System.nanoTime();

        log.info("开始执行 skill 脚本，skillName={}, scriptPath={}, arguments={}",
                metadata.getName(),
                normalizedScriptPath,
                safeArguments);

        if (!Files.exists(resolvedScriptPath) || !Files.isRegularFile(resolvedScriptPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "script 资源不存在: " + normalizedScriptPath);
        }

        validateRequiredArguments(argumentSpecs, safeArguments);

        String scriptContent = Files.readString(resolvedScriptPath, StandardCharsets.UTF_8);
        String wrappedCode = PythonScriptRuntimeUtils.buildWrappedCode(objectMapper, arguments, scriptContent);
        String result = pythonTool.apply(new PythonTool.PythonRequest(wrappedCode), null);
        log.info("skill 脚本执行完成，skillName={}, scriptPath={}, durationMs={}, resultLength={}, resultSnippet={}",
                metadata.getName(),
                normalizedScriptPath,
                elapsedMillis(startNanos),
                result == null ? 0 : result.length(),
                truncateForLog(result));

        return SkillScriptExecutionVO.builder()
                .skillName(metadata.getName())
                .scriptPath(normalizedScriptPath)
                .arguments(safeArguments)
                .result(result)
                .engine("spring-ai-alibaba-python-tool")
                .build();
    }

    /**
     * 计算从开始时间到当前的耗时毫秒数。
     *
     * @param startNanos 开始时记录的纳秒时间戳。
     * @return 已过去的毫秒数。
     */
    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * 从 skill 主内容中解析某个脚本入口对应的参数定义。
     *
     * @param skillName skill 名称。
     * @param scriptPath 脚本相对路径。
     * @return 参数定义映射。
     * @throws IOException 读取 skill 内容失败时抛出。
     */
    @Override
    public Map<String, SkillArgumentSpec> resolveSkillArgumentSpecs(String skillName, String scriptPath) throws IOException {
        SkillMetadata metadata = getSkillMetadataInternal(skillName);
        String normalizedScriptPath = normalizeResourcePath(scriptPath, RESOURCE_TYPE_SCRIPT);
        String skillContent = skillRegistry.readSkillContent(metadata.getName());
        return parseArgumentSpecsFromSkillContent(skillContent, normalizedScriptPath);
    }

    /**
     * 将 skill 元数据实体转换成对外返回的 VO。
     *
     * @param metadata skill 元数据实体。
     * @return skill 元数据 VO。
     */
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

    /**
     * 查询 skill 元数据实体，供内部资源读取和脚本执行使用。
     *
     * @param skillName skill 名称。
     * @return skill 元数据实体。
     */
    private SkillMetadata getSkillMetadataInternal(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "skillName 不能为空");
        }
        Optional<SkillMetadata> metadataOptional = skillRegistry.get(skillName.trim());
        return metadataOptional.orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "skill 不存在")
        );
    }

    /**
     * 规范化资源类型，并校验其是否属于支持的分类。
     *
     * @param resourceType 原始资源类型。
     * @return 规范化后的资源类型。
     */
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

    /**
     * 规范化资源路径，并阻止绝对路径和目录穿越。
     *
     * @param resourcePath 原始资源路径。
     * @param resourceType 资源类型。
     * @return 规范化后的资源路径。
     */
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

    /**
     * 截断日志中的脚本输出文本，避免日志过长。
     *
     * @param text 原始输出文本。
     * @return 截断后的文本。
     */
    private String truncateForLog(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500) + "...(truncated)";
    }

    /**
     * 从 `SKILL.md` 中解析指定脚本入口的参数定义表。
     *
     * @param skillContent skill 主内容文本。
     * @param scriptPath 目标脚本路径。
     * @return 参数名到参数定义的映射。
     */
    private Map<String, SkillArgumentSpec> parseArgumentSpecsFromSkillContent(String skillContent, String scriptPath) {
        if (skillContent == null || skillContent.isBlank() || scriptPath == null || scriptPath.isBlank()) {
            return Map.of();
        }
        String[] lines = skillContent.split("\\R");
        boolean targetScriptMatched = false;
        boolean inArgumentsSection = false;
        Map<String, SkillArgumentSpec> argumentSpecs = new LinkedHashMap<>();

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("### ")) {
                inArgumentsSection = false;
                if (!line.startsWith("### Script")) {
                    targetScriptMatched = false;
                }
            }

            java.util.regex.Matcher scriptMatcher = SCRIPT_PATH_LINE.matcher(line);
            if (scriptMatcher.matches()) {
                targetScriptMatched = scriptPath.equals(scriptMatcher.group(1).trim());
                inArgumentsSection = false;
                continue;
            }

            if (!targetScriptMatched) {
                continue;
            }

            if (line.startsWith("#### arguments")) {
                inArgumentsSection = true;
                continue;
            }

            if (inArgumentsSection && line.startsWith("#### ")) {
                break;
            }

            if (!inArgumentsSection) {
                continue;
            }

            java.util.regex.Matcher argumentMatcher = ARGUMENT_LINE.matcher(line);
            if (!argumentMatcher.matches()) {
                continue;
            }

            String argumentName = argumentMatcher.group(1).trim();
            String type = argumentMatcher.group(2).trim();
            boolean required = "required".equalsIgnoreCase(argumentMatcher.group(3).trim());
            String defaultValueText = argumentMatcher.group(4) == null ? "" : argumentMatcher.group(4).trim();
            String displayName = argumentMatcher.group(5) == null ? "" : argumentMatcher.group(5).trim();

            argumentSpecs.put(argumentName, SkillArgumentSpec.builder()
                    .name(argumentName)
                    .displayName(displayName.isBlank() ? argumentName : displayName)
                    .type(type)
                    .required(required)
                    .optional(!required)
                    .defaultValue(parseDefaultValue(defaultValueText, type))
                    .build());
        }

        return argumentSpecs;
    }

    /**
     * 解析参数定义里声明的默认值文本。
     *
     * @param rawValue 默认值文本。
     * @param type 参数类型。
     * @return 解析后的默认值对象。
     */
    private Object parseDefaultValue(String rawValue, String type) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalizedType = type == null ? "" : type.trim().toLowerCase();
        String value = rawValue.trim();
        try {
            if ("number".equals(normalizedType) || "integer".equals(normalizedType) || "float".equals(normalizedType)
                    || "double".equals(normalizedType)) {
                return value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value);
            }
            if ("boolean".equals(normalizedType)) {
                return Boolean.parseBoolean(value);
            }
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    /**
     * 校验 `validate Required Arguments` 对应内容。
     *
     * @param argumentSpecs argumentSpecs 参数。
     * @param arguments arguments 参数。
     */
    private void validateRequiredArguments(Map<String, SkillArgumentSpec> argumentSpecs, Map<String, Object> arguments) {
        if (argumentSpecs == null || argumentSpecs.isEmpty()) {
            return;
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        for (Map.Entry<String, SkillArgumentSpec> entry : argumentSpecs.entrySet()) {
            SkillArgumentSpec spec = entry.getValue();
            if (spec == null || !Boolean.TRUE.equals(spec.getRequired())) {
                continue;
            }
            Object value = safeArguments.get(entry.getKey());
            if (value == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, buildRequiredArgumentMessage(entry.getKey(), spec));
            }
            if (value instanceof String text && text.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, buildRequiredArgumentMessage(entry.getKey(), spec));
            }
        }
    }

    /**
     * 构造缺少必填参数时的错误消息。
     *
     * @param argumentName 参数名。
     * @param spec 参数定义。
     * @return 错误消息文本。
     */
    private String buildRequiredArgumentMessage(String argumentName, SkillArgumentSpec spec) {
        String displayName = spec == null ? "" : Optional.ofNullable(spec.getDisplayName()).orElse("").trim();
        String fallbackName = spec == null ? "" : Optional.ofNullable(spec.getName()).orElse("").trim();
        String finalName = !displayName.isEmpty() ? displayName : (!fallbackName.isEmpty() ? fallbackName : argumentName);
        return finalName + "不能为空";
    }

    /**
     * 解析 skill 在本地文件系统中的根目录。
     *
     * @param metadata skill 元数据实体。
     * @return skill 根目录路径。
     */
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

    /**
     * 根据资源类型和相对路径解析出实际文件路径。
     *
     * @param skillBasePath skill 根目录。
     * @param resourceType 资源类型。
     * @param normalizedResourcePath 规范化后的资源路径。
     * @return 实际文件路径。
     */
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

    /**
     * 查询 `list Files Under` 对应集合。
     *
     * @param skillBasePath skillBasePath 参数。
     * @param directoryName directoryName 参数。
     * @return 返回处理结果。
     * @throws IOException 异常信息。
     */
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

    /**
     * 根据行号范围裁剪资源内容，并构建统一的返回对象。
     *
     * @param skillName skill 名称。
     * @param resourceType 资源类型。
     * @param resourcePath 资源路径。
     * @param content 原始内容。
     * @param startLine 起始行号。
     * @param endLine 结束行号。
     * @return 资源内容读取结果。
     */
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

    /**
     * 规范化起始行号，保证其落在有效范围内。
     *
     * @param startLine 原始起始行号。
     * @param totalLines 总行数。
     * @return 规范化后的起始行号。
     */
    private int normalizeStartLine(Integer startLine, int totalLines) {
        if (startLine == null || startLine <= 0) {
            return 1;
        }
        return Math.min(startLine, totalLines);
    }

    /**
     * 规范化结束行号，保证其不早于起始行且不超过总行数。
     *
     * @param endLine 原始结束行号。
     * @param startLine 规范化后的起始行号。
     * @param totalLines 总行数。
     * @return 规范化后的结束行号。
     */
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

    /**
     * 按给定行号范围拼接文本内容。
     *
     * @param lines 原始行数组。
     * @param startLine 起始行号。
     * @param endLine 结束行号。
     * @return 截取后的文本内容。
     */
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
