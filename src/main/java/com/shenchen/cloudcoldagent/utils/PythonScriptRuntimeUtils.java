package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;

import java.util.Map;

/**
 * `PythonScriptRuntimeUtils` 类型实现。
 */
public final class PythonScriptRuntimeUtils {

    /**
     * 创建 `PythonScriptRuntimeUtils` 实例。
     */
    private PythonScriptRuntimeUtils() {
    }

    /**
     * 构建 `build Wrapped Code` 对应结果。
     *
     * @param objectMapper objectMapper 参数。
     * @param arguments arguments 参数。
     * @param scriptContent scriptContent 参数。
     * @return 返回处理结果。
     */
    public static String buildWrappedCode(ObjectMapper objectMapper, Map<String, Object> arguments, String scriptContent) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        try {
            String argumentsJson = objectMapper.writeValueAsString(safeArguments);
            return """
                    import json

                    __skill_args = json.loads(%s)
                    args = __skill_args

                    %s
                    """.formatted(toPythonStringLiteral(argumentsJson), scriptContent == null ? "" : scriptContent);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "脚本参数序列化失败");
        }
    }

    /**
     * 处理 `to Python String Literal` 对应逻辑。
     *
     * @param text text 参数。
     * @return 返回处理结果。
     */
    private static String toPythonStringLiteral(String text) {
        String safeText = text == null ? "" : text;
        return "\"\"\"" + safeText.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"") + "\"\"\"";
    }
}
