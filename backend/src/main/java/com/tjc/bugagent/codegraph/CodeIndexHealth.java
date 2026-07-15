package com.tjc.bugagent.codegraph;

/**
 * 当前项目版本的代码索引健康信息。
 */
public class CodeIndexHealth {
    private final int nodeCount;
    private final int locatedNodeCount;
    private final boolean routeMatched;
    private final boolean targetLocated;

    /** 创建一次索引健康快照。 */
    public CodeIndexHealth(int nodeCount, int locatedNodeCount, boolean routeMatched, boolean targetLocated) {
        this.nodeCount = nodeCount;
        this.locatedNodeCount = locatedNodeCount;
        this.routeMatched = routeMatched;
        this.targetLocated = targetLocated;
    }

    /** 返回给 Agent 的索引状态和降级建议。 */
    public String summary() {
        if (nodeCount <= 0) {
            return "EMPTY（当前版本无代码节点；直接使用 grep_source，并按 sourceRef 调用 read_source）";
        }
        if (locatedNodeCount <= 0 || !routeMatched || !targetLocated) {
            return "PARTIAL（节点=" + nodeCount + "，带源码定位=" + locatedNodeCount
                    + "，目标接口路由=" + (routeMatched ? "已命中" : "未命中")
                    + "，目标源码定位=" + (targetLocated ? "可用" : "不可用")
                    + "；节点读取失败时直接使用 grep_source/read_source）";
        }
        return "HEALTHY（节点=" + nodeCount + "，带源码定位=" + locatedNodeCount
                + "，目标接口路由及源码定位均可用）";
    }

    /** 返回代码节点总数。 */
    public int getNodeCount() { return nodeCount; }
    /** 返回带源码定位的节点数。 */
    public int getLocatedNodeCount() { return locatedNodeCount; }
    /** 返回目标接口路由是否命中。 */
    public boolean isRouteMatched() { return routeMatched; }
    /** 返回目标接口是否具备源码定位。 */
    public boolean isTargetLocated() { return targetLocated; }
}
