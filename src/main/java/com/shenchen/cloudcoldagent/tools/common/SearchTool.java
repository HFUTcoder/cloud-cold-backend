package com.shenchen.cloudcoldagent.tools.common;

import com.alibaba.cloud.ai.toolcalling.common.interfaces.SearchService;
import com.shenchen.cloudcoldagent.config.properties.SearchProperties;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * `SearchTool` 类型实现。
 */
@Component
public class SearchTool extends BaseTool {

    private static final String TOOL_NAME = "search";

    private final SearchService searchService;

    /**
     * 创建 `SearchTool` 实例。
     *
     * @param searchService searchService 参数。
     * @param searchProperties searchProperties 参数。
     */
    public SearchTool(SearchService searchService, SearchProperties searchProperties) {
        super(searchProperties.getMock().isEnabled());
        this.searchService = searchService;
    }

    /**
     * 处理 `search` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     */
    @Tool(name = "search", description = "联网搜索工具，用于检索最新网页信息")
    public String search(@ToolParam(description = "查询语句") String query) {
        logToolStart(TOOL_NAME, "query", query);
        if (query == null || query.isBlank()) {
            return "请提供查询语句";
        }

        if (isMockEnabled()) {
            String mockResult = mockSearchResult(query.trim());
            logToolMock(TOOL_NAME, query.trim(), mockResult);
            return mockResult;
        }

        try {
            SearchService.Response response = searchService.query(query.trim());
            String formattedResult = formatSearchResponse(query.trim(), response);
            logToolSuccess(TOOL_NAME, query.trim(), formattedResult);
            return formattedResult;
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, query, e, "联网搜索执行失败：");
        }
    }

    /**
     * 处理 `format Search Response` 对应逻辑。
     *
     * @param query query 参数。
     * @param response response 参数。
     * @return 返回处理结果。
     */
    private String formatSearchResponse(String query, SearchService.Response response) {
        if (response == null || response.getSearchResult() == null) {
            return "未检索到搜索结果。";
        }

        var results = response.getSearchResult().results();
        if (results == null || results.isEmpty()) {
            return "未检索到搜索结果。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("搜索词：").append(query).append("\n");
        sb.append("搜索结果摘要（前 ").append(Math.min(results.size(), 5)).append(" 条）：\n");

        for (int i = 0; i < results.size() && i < 5; i++) {
            SearchService.SearchContent item = results.get(i);
            sb.append(i + 1).append(". 标题：")
                    .append(defaultText(item.title()))
                    .append("\n")
                    .append("链接：")
                    .append(defaultText(item.url()))
                    .append("\n")
                    .append("摘要：")
                    .append(defaultText(item.content()))
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 处理 `mock Search Result` 对应逻辑。
     *
     * @param query query 参数。
     * @return 返回处理结果。
     */
    private String mockSearchResult(String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("【MOCK联网搜索结果】\n");
        sb.append("搜索词：").append(query).append("\n");
        sb.append("搜索结果摘要（前 3 条）：\n");

        if (query.contains("预警") || query.contains("天气")) {
            sb.append("""
                    1. 标题：北京市气象台发布周末天气提示（MOCK）
                    链接：https://mock.local/beijing-weather-alert
                    摘要：北京市本周末有弱冷空气活动，预计周六白天多云，周日有分散性小雨，当前暂无高级别灾害天气预警，建议关注临近预报。
                    2. 标题：北京周末出行气象服务专报（MOCK）
                    链接：https://mock.local/beijing-weekend-forecast
                    摘要：周末气温在14C至22C之间，早晚温差较大，适合短途出游，山区可能有阵风，外出建议携带薄外套。
                    3. 标题：北京文旅出行安全提醒（MOCK）
                    链接：https://mock.local/beijing-travel-reminder
                    摘要：热门景区客流正常，如遇阵雨请优先选择室内展馆类景点，并关注景区实时公告。
                    """);
            return sb.toString();
        }

        if (query.contains("景点") || query.contains("旅游") || query.contains("打卡")) {
            sb.append("""
                    1. 标题：北京周末适合打卡的城市景点推荐（MOCK）
                    链接：https://mock.local/beijing-spots
                    摘要：国家博物馆、颐和园、北海公园、亮马河夜游和798艺术区适合周末出行，可根据天气灵活安排室内外行程。
                    2. 标题：北京阴雨天气备选玩法指南（MOCK）
                    链接：https://mock.local/beijing-indoor-guide
                    摘要：若遇降雨，可优先选择首都博物馆、中国电影博物馆、PageOne书店等室内场所，减少天气影响。
                    3. 标题：北京亲子与轻徒步线路整理（MOCK）
                    链接：https://mock.local/beijing-family-trips
                    摘要：温榆河公园、奥林匹克森林公园和园博园适合天气稳定时游玩，注意避开大风和晚间降温时段。
                    """);
            return sb.toString();
        }

        sb.append("""
                1. 标题：综合搜索结果一（MOCK）
                链接：https://mock.local/general-result-1
                摘要：这是一条固定的调试用搜索结果，用于验证 Agent 的工具调用、日志输出和结果整合流程。
                2. 标题：综合搜索结果二（MOCK）
                链接：https://mock.local/general-result-2
                摘要：当前项目已启用搜索工具模拟模式，结果内容为本地写死数据，不会消耗真实联网搜索额度。
                3. 标题：综合搜索结果三（MOCK）
                链接：https://mock.local/general-result-3
                摘要：调试完成后可关闭 cloudcold.search.mock.enabled 配置，恢复真实联网搜索。
                """);
        return sb.toString();
    }
}
