package com.shenchen.cloudcoldagent.document.extract.cleaner;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档清洗工具
 */
public class DocumentCleaner {

    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("<image\\b[^>]*?>.*?</image>", Pattern.DOTALL);
    private static final Pattern INLINE_SPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f]+");

    /**
     * 处理 `clean Documents` 对应逻辑。
     *
     * @param documents documents 参数。
     * @return 返回处理结果。
     */
    public static List<Document> cleanDocuments(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return documents;
        }

        return documents.stream()
                .map(DocumentCleaner::cleanDocument)
                .collect(Collectors.toList());
    }

    /**
     * 处理 `clean Document` 对应逻辑。
     *
     * @param doc doc 参数。
     * @return 返回处理结果。
     */
    private static Document cleanDocument(Document doc) {
        if (doc == null || doc.getText() == null) {
            return doc;
        }

        String text = normalizeNewlines(doc.getText());
        List<String> protectedImageTags = new ArrayList<>();
        text = protectImageTags(text, protectedImageTags);

        String[] lines = text.split("\n", -1);
        List<String> cleanedLines = new ArrayList<>();
        boolean previousEmpty = false;

        for (String line : lines) {
            String cleanedLine = cleanLine(line);
            if (cleanedLine.isEmpty()) {
                if (!cleanedLines.isEmpty() && !previousEmpty) {
                    cleanedLines.add("");
                }
                previousEmpty = true;
                continue;
            }

            cleanedLines.add(cleanedLine);
            previousEmpty = false;
        }

        List<String> deduplicatedLines = deduplicateLines(cleanedLines);
        String cleanedText = restoreImageTags(String.join("\n", deduplicatedLines), protectedImageTags).trim();
        Map<String, Object> metadata = doc.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(doc.getMetadata());

        return doc.mutate()
                .text(cleanedText)
                .metadata(metadata)
                .build();
    }

    /**
     * 处理 `normalize Newlines` 对应逻辑。
     *
     * @param text text 参数。
     * @return 返回处理结果。
     */
    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 处理 `protect Image Tags` 对应逻辑。
     *
     * @param text text 参数。
     * @param protectedImageTags protectedImageTags 参数。
     * @return 返回处理结果。
     */
    private static String protectImageTags(String text, List<String> protectedImageTags) {
        Matcher matcher = IMAGE_TAG_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        int index = 0;
        while (matcher.find()) {
            String placeholder = "__IMAGE_TAG_" + index++ + "__";
            protectedImageTags.add(matcher.group());
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 处理 `clean Line` 对应逻辑。
     *
     * @param line line 参数。
     * @return 返回处理结果。
     */
    private static String cleanLine(String line) {
        String normalizedSpace = INLINE_SPACE_PATTERN.matcher(line).replaceAll(" ").trim();
        return removeControlCharacters(normalizedSpace);
    }

    /**
     * 删除 `remove Control Characters` 对应内容。
     *
     * @param text text 参数。
     * @return 返回处理结果。
     */
    private static String removeControlCharacters(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!Character.isISOControl(current)) {
                cleaned.append(current);
            }
        }
        return cleaned.toString();
    }

    /**
     * 处理 `deduplicate Lines` 对应逻辑。
     *
     * @param lines lines 参数。
     * @return 返回处理结果。
     */
    private static List<String> deduplicateLines(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> deduplicated = new ArrayList<>();
        boolean pendingBlankLine = false;

        for (String line : lines) {
            if (line.isEmpty()) {
                pendingBlankLine = !deduplicated.isEmpty();
                continue;
            }

            if (seen.add(line)) {
                if (pendingBlankLine) {
                    deduplicated.add("");
                    pendingBlankLine = false;
                }
                deduplicated.add(line);
            }
        }
        return deduplicated;
    }

    /**
     * 处理 `restore Image Tags` 对应逻辑。
     *
     * @param text text 参数。
     * @param protectedImageTags protectedImageTags 参数。
     * @return 返回处理结果。
     */
    private static String restoreImageTags(String text, List<String> protectedImageTags) {
        String restored = text;
        for (int i = 0; i < protectedImageTags.size(); i++) {
            restored = restored.replace("__IMAGE_TAG_" + i + "__", protectedImageTags.get(i));
        }
        return restored;
    }
}
