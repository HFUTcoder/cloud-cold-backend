package com.shenchen.cloudcoldagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SearchService {

    @Tool(name = "search", description = "搜索工具", returnDirect = true)
    public String search(@ToolParam(description = "查询语句") String query) {
        log.info("EXECUTE Tool: search query: {}", query);
        if (query == null) {
            return "请提供查询语句";
        }
        return """
            根据最新气象预测，未来哈尔滨一周天气将经历明显的极端变化和寒潮影响。12日预计出现小雪，城区降雪量1-3毫米，气温在-2℃至2℃之间，路面湿滑，市民需注意出行安全。13日暴雪来袭，降雪量可能达到30-50毫米，并伴随6-9级大风，交通、电力、供暖等基础设施可能受到影响。14日中雪持续，最低气温进一步降低，夜间寒意明显，需加强防寒防滑措施。15日多云，气温略回升，但昼夜温差较大。16日至18日连续晴好天气，白天气温在-10℃至0℃之间，夜间最低温度可达-17℃，阳光充足但寒风依旧强烈。总体来看，本周哈尔滨天气特点为“雪量大、降温猛、风力强、持续久”，城市运行可能面临交通拥堵、道路结冰、电力负荷增加等挑战。市民和相关部门应提前做好应急准备，包括防寒保暖、道路除雪和安全检查等。同时，需关注天气变化，合理安排户外活动和交通出行，确保生活和生产安全。此轮寒潮叠加降雪过程将对空气质量、公共交通和能源供应产生一定影响，因此务必保持警惕，及时获取官方气象预报和通知。
            """;
    }
}
