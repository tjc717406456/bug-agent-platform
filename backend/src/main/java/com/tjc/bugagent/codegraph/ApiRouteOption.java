package com.tjc.bugagent.codegraph;

/**
 * 前端选择接口地址时使用的路由选项。
 */
public class ApiRouteOption {
    private Long id;
    private String path;
    private String filePath;
    private Integer lineNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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
}
