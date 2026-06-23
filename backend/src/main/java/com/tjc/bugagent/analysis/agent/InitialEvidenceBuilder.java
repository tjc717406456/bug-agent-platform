package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.log.LogClues;

/**
 * 组装多轮分析的初始证据：基础信息、日志线索、命中路由、SQL、表，
 * 并预取异常堆栈栈帧和入口/调用节点的源码快照，让多数分析在前一两轮就能收敛。
 */
@Component
public class InitialEvidenceBuilder {

    // 异常堆栈定位：取栈顶前几个业务栈帧，各读一小段源码
    private static final int STACK_FRAME_LIMIT = 3;
    private static final int STACK_SNIPPET_LINES = 24;
    private static final int SNAPSHOT_RELATED_LIMIT = 4;
    private static final int SNAPSHOT_SNIPPET_LINES = 30;
    // 匹配 "at com.x.Y.method(Y.java:123)" 形式的栈帧
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile("at\\s+([\\w.$]+)\\.([\\w$<>]+)\\([^)]*?:(\\d+)\\)");
    // 框架/JDK 包前缀，这些栈帧对定位业务 bug 没价值，跳过
    private static final String[] STACK_SKIP_PREFIXES = {
            "java.", "javax.", "jakarta.", "sun.", "jdk.", "com.sun.",
            "org.springframework.", "org.apache.", "org.mybatis.", "com.baomidou.",
            "org.hibernate.", "com.mysql.", "com.zaxxer.", "com.fasterxml.",
            "ch.qos.", "org.slf4j.", "io.netty.", "reactor.", "org.junit."
    };

    private final SourceReader sourceReader;
    private final AppProperties appProperties;

    public InitialEvidenceBuilder(SourceReader sourceReader, AppProperties appProperties) {
        this.sourceReader = sourceReader;
        this.appProperties = appProperties;
    }

    public String buildInitialEvidence(AnalysisRequest request, ProjectVersion version,
                                       CodeGraphQueryResult graph, ProjectDatasource datasource,
                                       AgentToolExecutor.AgentToolContext toolContext, LogClues logClues) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目ID: ").append(request.getProjectId()).append("\n");
        builder.append("版本ID: ").append(version.getId()).append("\n");
        builder.append("API路径: ").append(request.getApiPath()).append("\n");
        appendIfPresent(builder, "用户描述", request.getUserDescription());
        appendIfPresent(builder, "请求参数", request.getRequestBody());
        appendIfPresent(builder, "响应结果", request.getResponseBody());
        appendIfPresent(builder, "异常堆栈", request.getStackTrace());
        String stackSnapshots = buildStackSnapshots(request.getStackTrace(), toolContext);
        if (!stackSnapshots.isEmpty()) {
            builder.append("异常堆栈定位(栈顶业务代码，报错的直接位置，优先从这里判断根因):\n").append(stackSnapshots).append("\n");
        }
        appendIfPresent(builder, "截图保存路径", request.getScreenshotPaths());
        appendIfPresent(builder, "Trace ID", request.getTraceId());
        appendIfPresent(builder, "请求时间", request.getRequestTime());
        builder.append("数据源: ").append(datasource == null ? "未配置" : datasource.getDbhubKey()).append("\n");
        // 日志证据放在路由/源码这些大块之前，避免总量截断时被砍掉——日志里常有最直接的答案
        if (logClues != null && !logClues.getRelevantLines().isEmpty()) {
            builder.append("日志关注行(按接口名/关键词从日志匹配出的相关行):\n")
                    .append(String.join("\n", logClues.getRelevantLines())).append("\n");
        }
        if (logClues != null && !logClues.getSqlLines().isEmpty()) {
            builder.append("日志中的SQL:\n").append(String.join("\n", logClues.getSqlLines())).append("\n");
        }
        if (logClues != null && !logClues.getErrorLines().isEmpty()) {
            builder.append("日志中的ERROR:\n").append(String.join("\n", logClues.getErrorLines())).append("\n");
        }
        builder.append("命中路由:\n").append(formatNodes(graph.getRouteNodes())).append("\n");
        builder.append("相关调用节点:\n").append(formatNodes(graph.getRelatedNodes())).append("\n");
        AppProperties.Agent agentConfig = appProperties.getAgent();
        String sqlText = graph.getSqlTexts().isEmpty() ? "无" : trim(String.join("\n", graph.getSqlTexts()), agentConfig.getSqlTextLimit());
        builder.append("SQL摘要:\n").append(sqlText).append("\n");
        builder.append("涉及表: ").append(graph.getTables()).append("\n");
        builder.append("源码快照(已自动预取入口与关键调用节点的代码，无需再次读取相同位置):\n")
                .append(buildSourceSnapshots(graph, toolContext)).append("\n");
        // 总量兜底截断，防止大接口的初始证据撑爆上下文（多轮对话每次都会带上）
        return trim(builder.toString(), agentConfig.getInitialEvidenceLimit());
    }

    /**
     * 解析异常堆栈，把栈顶业务栈帧对应的源码片段预取进初始证据。
     * 有堆栈时这是最强定位信号——栈帧直接写明报错的类和行号。
     */
    private String buildStackSnapshots(String stackTrace, AgentToolExecutor.AgentToolContext toolContext) {
        List<StackFrame> frames = parseBusinessFrames(stackTrace);
        if (frames.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (StackFrame frame : frames) {
            String snippet = sourceReader.readSourceAtClassLine(toolContext.getProjectId(), toolContext.getVersionId(),
                    frame.className, frame.lineNo, STACK_SNIPPET_LINES);
            builder.append("【").append(frame.className).append("#").append(frame.methodName)
                    .append(":").append(frame.lineNo).append("】\n")
                    .append(snippet).append("\n");
        }
        return builder.toString();
    }

    /**
     * 从堆栈文本里抽取业务栈帧，过滤框架/JDK 帧，按出现顺序取前 STACK_FRAME_LIMIT 个并去重。
     */
    private List<StackFrame> parseBusinessFrames(String stackTrace) {
        List<StackFrame> frames = new ArrayList<StackFrame>();
        if (isBlank(stackTrace)) {
            return frames;
        }
        // 有 "Caused by" 链时，根因在最后一段，优先从那解析，定位更贴近真实出错点
        int rootCause = stackTrace.lastIndexOf("Caused by:");
        String scope = rootCause >= 0 ? stackTrace.substring(rootCause) : stackTrace;
        Matcher matcher = STACK_FRAME_PATTERN.matcher(scope);
        Set<String> seen = new HashSet<String>();
        while (matcher.find() && frames.size() < STACK_FRAME_LIMIT) {
            String className = matcher.group(1);
            if (isFrameworkFrame(className)) {
                continue;
            }
            int lineNo;
            try {
                lineNo = Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (seen.add(className + ":" + lineNo)) {
                frames.add(new StackFrame(className, matcher.group(2), lineNo));
            }
        }
        return frames;
    }

    private boolean isFrameworkFrame(String className) {
        for (String prefix : STACK_SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 预取路由入口与直接关联节点的源码片段，塞进初始证据，
     * 让多数分析在第 1-2 轮即可收敛，省掉来回 get_code_detail 的串行 LLM 往返。
     */
    private String buildSourceSnapshots(CodeGraphQueryResult graph, AgentToolExecutor.AgentToolContext toolContext) {
        List<CodeNode> targets = new ArrayList<CodeNode>();
        Set<String> seen = new HashSet<String>();
        collectSnapshotTargets(targets, seen, graph.getRouteNodes(), Integer.MAX_VALUE);
        collectSnapshotTargets(targets, seen, graph.getRelatedNodes(), SNAPSHOT_RELATED_LIMIT);
        if (targets.isEmpty()) {
            return "无（无可定位的源码节点）";
        }
        StringBuilder builder = new StringBuilder();
        for (CodeNode node : targets) {
            String snippet = sourceReader.readSnippet(node, toolContext.getProjectId(), toolContext.getVersionId(), SNAPSHOT_SNIPPET_LINES);
            builder.append("【").append(node.getName()).append("】 ")
                    .append(node.getFilePath()).append(":").append(node.getLineNo()).append("\n")
                    .append(snippet).append("\n");
        }
        return builder.toString();
    }

    private void collectSnapshotTargets(List<CodeNode> targets, Set<String> seen, List<CodeNode> source, int limit) {
        if (source == null) {
            return;
        }
        int added = 0;
        for (CodeNode node : source) {
            if (added >= limit) {
                break;
            }
            if (node.getFilePath() == null || node.getLineNo() == null) {
                continue;
            }
            String key = node.getFilePath() + ":" + node.getLineNo();
            if (seen.add(key)) {
                targets.add(node);
                added++;
            }
        }
    }

    private String formatNodes(List<CodeNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "无";
        }
        return nodes.stream()
                .map(node -> "nodeId=" + node.getId()
                        + ", type=" + node.getNodeType()
                        + ", name=" + node.getName()
                        + ", qualifiedName=" + node.getQualifiedName()
                        + ", file=" + node.getFilePath()
                        + ", line=" + node.getLineNo()
                        + ", metadata=" + node.getMetadataJson())
                .collect(Collectors.joining("\n"));
    }

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append(label).append(": ").append(value).append("\n");
        }
    }

    /**
     * 一条堆栈栈帧的关键信息。
     */
    private static final class StackFrame {
        private final String className;
        private final String methodName;
        private final int lineNo;

        private StackFrame(String className, String methodName, int lineNo) {
            this.className = className;
            this.methodName = methodName;
            this.lineNo = lineNo;
        }
    }
}
