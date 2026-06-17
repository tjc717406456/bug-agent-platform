package com.tjc.bugagent.codegraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Code graph evidence for one API path.
 */
public class CodeGraphQueryResult {
    private List<CodeNode> routeNodes = new ArrayList<CodeNode>();
    private List<CodeNode> relatedNodes = new ArrayList<CodeNode>();
    private List<String> sqlTexts = new ArrayList<String>();
    private List<String> tables = new ArrayList<String>();

    public List<CodeNode> getRouteNodes() {
        return routeNodes;
    }

    public void setRouteNodes(List<CodeNode> routeNodes) {
        this.routeNodes = routeNodes;
    }

    public List<CodeNode> getRelatedNodes() {
        return relatedNodes;
    }

    public void setRelatedNodes(List<CodeNode> relatedNodes) {
        this.relatedNodes = relatedNodes;
    }

    public List<String> getSqlTexts() {
        return sqlTexts;
    }

    public void setSqlTexts(List<String> sqlTexts) {
        this.sqlTexts = sqlTexts;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }
}

