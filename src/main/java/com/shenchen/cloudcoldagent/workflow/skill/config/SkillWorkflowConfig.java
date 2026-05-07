package com.shenchen.cloudcoldagent.workflow.skill.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.shenchen.cloudcoldagent.workflow.skill.node.BuildSkillRuntimeContextNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.DiscoverCandidateSkillsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.LoadBoundSkillsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.LoadConversationHistoryNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.LoadSkillContentsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.RecognizeBoundSkillsNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `SkillWorkflowConfig` 类型实现。
 */
@Configuration
public class SkillWorkflowConfig {

    /**
     * 处理 `skill Workflow Graph` 对应逻辑。
     *
     * @param loadBoundSkillsNode loadBoundSkillsNode 参数。
     * @param loadConversationHistoryNode loadConversationHistoryNode 参数。
     * @param recognizeBoundSkillsNode recognizeBoundSkillsNode 参数。
     * @param discoverCandidateSkillsNode discoverCandidateSkillsNode 参数。
     * @param loadSkillContentsNode loadSkillContentsNode 参数。
     * @param buildSkillRuntimeContextNode buildSkillRuntimeContextNode 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @Bean
    public CompiledGraph skillWorkflowGraph(LoadBoundSkillsNode loadBoundSkillsNode,
                                            LoadConversationHistoryNode loadConversationHistoryNode,
                                            RecognizeBoundSkillsNode recognizeBoundSkillsNode,
                                            DiscoverCandidateSkillsNode discoverCandidateSkillsNode,
                                            LoadSkillContentsNode loadSkillContentsNode,
                                            BuildSkillRuntimeContextNode buildSkillRuntimeContextNode) throws Exception {
        StateGraph graph = new StateGraph();
        graph.addNode("loadBoundSkills", loadBoundSkillsNode::apply);
        graph.addNode("loadConversationHistory", loadConversationHistoryNode::apply);
        graph.addNode("recognizeBoundSkills", recognizeBoundSkillsNode::apply);
        graph.addNode("discoverCandidateSkills", discoverCandidateSkillsNode::apply);
        graph.addNode("loadSkillContents", loadSkillContentsNode::apply);
        graph.addNode("buildSkillRuntimeContext", buildSkillRuntimeContextNode::apply);

        graph.addEdge(StateGraph.START, "loadBoundSkills");
        graph.addEdge("loadBoundSkills", "loadConversationHistory");
        graph.addEdge("loadConversationHistory", "recognizeBoundSkills");
        graph.addEdge("recognizeBoundSkills", "discoverCandidateSkills");
        graph.addEdge("discoverCandidateSkills", "loadSkillContents");
        graph.addEdge("loadSkillContents", "buildSkillRuntimeContext");
        graph.addEdge("buildSkillRuntimeContext", StateGraph.END);
        return graph.compile();
    }
}
