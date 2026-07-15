package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口讲解流程证据收敛测试。
 */
class ApiExplainProgressTest {

    @Test
    void repeatedWindowsInSameFileDoNotCountAsNewFlowEvidence() {
        ApiExplainService.ExplainProgress progress = new ApiExplainService.ExplainProgress(new CodeGraphQueryResult());

        assertTrue(progress.record(readSource("src/main/java/demo/Service.java", 20),
                sourceResult("src/main/java/demo/Service.java", 20)));
        assertFalse(progress.record(readSource("src/main/java/demo/Service.java", 120),
                sourceResult("src/main/java/demo/Service.java", 120)));
    }

    @Test
    void coreFlowIsCoveredAfterEntryAndTwoSourceFiles() {
        CodeGraphQueryResult graph = new CodeGraphQueryResult();
        graph.setRouteNodes(Collections.singletonList(node("Controller.java", 10)));
        graph.setRelatedNodes(Collections.singletonList(node("Service.java", 20)));
        ApiExplainService.ExplainProgress progress = new ApiExplainService.ExplainProgress(graph);

        assertTrue(progress.coreFlowCovered());
    }

    @Test
    void dataLayerToolAddsOnlyOneFlowStage() {
        ApiExplainService.ExplainProgress progress = new ApiExplainService.ExplainProgress(new CodeGraphQueryResult());
        AgentToolCall call = new AgentToolCall();
        call.setAction("describe_tables");

        assertTrue(progress.record(call, AgentToolResult.ok("describe_tables", "表结构", "id bigint")));
        assertFalse(progress.record(call, AgentToolResult.ok("describe_tables", "表结构", "name varchar")));
    }

    private AgentToolCall readSource(String filePath, int line) {
        AgentToolCall call = new AgentToolCall();
        call.setAction("read_source");
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("filePath", filePath);
        arguments.put("line", line);
        call.setArguments(arguments);
        return call;
    }

    private AgentToolResult sourceResult(String filePath, int line) {
        return AgentToolResult.ok("read_source", "读取源码",
                "sourceRef={\"filePath\":\"" + filePath + "\",\"line\":" + line + "}");
    }

    private CodeNode node(String filePath, int line) {
        CodeNode node = new CodeNode();
        node.setFilePath(filePath);
        node.setLineNo(line);
        return node;
    }
}
