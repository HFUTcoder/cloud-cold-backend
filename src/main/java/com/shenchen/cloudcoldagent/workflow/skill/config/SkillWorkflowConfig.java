package com.shenchen.cloudcoldagent.workflow.skill.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.shenchen.cloudcoldagent.workflow.skill.node.BuildEnhancedQuestionNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.BuildSkillExecutionPlansNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.DiscoverCandidateSkillsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.LoadBoundSkillsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.LoadSkillContentsNode;
import com.shenchen.cloudcoldagent.workflow.skill.node.RecognizeBoundSkillsNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkillWorkflowConfig {

    @Bean
    public CompiledGraph skillWorkflowGraph(LoadBoundSkillsNode loadBoundSkillsNode,
                                            RecognizeBoundSkillsNode recognizeBoundSkillsNode,
                                            DiscoverCandidateSkillsNode discoverCandidateSkillsNode,
                                            LoadSkillContentsNode loadSkillContentsNode,
                                            BuildSkillExecutionPlansNode buildSkillExecutionPlansNode,
                                            BuildEnhancedQuestionNode buildEnhancedQuestionNode) throws Exception {
        StateGraph graph = new StateGraph();
        graph.addNode("loadBoundSkills", loadBoundSkillsNode::apply);
        graph.addNode("recognizeBoundSkills", recognizeBoundSkillsNode::apply);
        graph.addNode("discoverCandidateSkills", discoverCandidateSkillsNode::apply);
        graph.addNode("loadSkillContents", loadSkillContentsNode::apply);
        graph.addNode("buildSkillExecutionPlans", buildSkillExecutionPlansNode::apply);
        graph.addNode("buildEnhancedQuestion", buildEnhancedQuestionNode::apply);

        graph.addEdge(StateGraph.START, "loadBoundSkills");
        graph.addEdge("loadBoundSkills", "recognizeBoundSkills");
        graph.addEdge("recognizeBoundSkills", "discoverCandidateSkills");
        graph.addEdge("discoverCandidateSkills", "loadSkillContents");
        graph.addEdge("loadSkillContents", "buildSkillExecutionPlans");
        graph.addEdge("buildSkillExecutionPlans", "buildEnhancedQuestion");
        graph.addEdge("buildEnhancedQuestion", StateGraph.END);
        return graph.compile();
    }
}
