package com.shenchen.cloudcoldagent.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;

import java.util.Map;

public final class PythonScriptRuntimeUtils {

    private PythonScriptRuntimeUtils() {
    }

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

    private static String toPythonStringLiteral(String text) {
        String safeText = text == null ? "" : text;
        return "\"\"\"" + safeText.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"") + "\"\"\"";
    }
}
