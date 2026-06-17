package com.tjc.bugagent.codegraph;

/**
 * Code graph node returned to the UI and agent.
 */
public class CodeNode {
    private Long id;
    private String nodeType;
    private String name;
    private String qualifiedName;
    private String filePath;
    private Integer lineNo;
    private String metadataJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    @Override
    public String toString() {
        return "CodeNode{" +
                "type='" + nodeType + '\'' +
                ", name='" + name + '\'' +
                ", qualifiedName='" + qualifiedName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", lineNo=" + lineNo +
                ", metadata=" + metadataJson +
                '}';
    }
}
