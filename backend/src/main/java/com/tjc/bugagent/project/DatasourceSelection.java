package com.tjc.bugagent.project;

/**
 * 一次分析实际可用的数据库范围。结构库可以跨环境复用，业务库必须与问题环境一致。
 */
public class DatasourceSelection {
    public static final String NONE = "NONE";
    public static final String SCHEMA_ONLY = "SCHEMA_ONLY";
    public static final String BUSINESS_DATA = "BUSINESS_DATA";

    private final String environment;
    private final String accessLevel;
    private final ProjectDatasource schemaDatasource;
    private final ProjectDatasource businessDatasource;

    public DatasourceSelection(String environment, String accessLevel, ProjectDatasource schemaDatasource,
                               ProjectDatasource businessDatasource) {
        this.environment = environment;
        this.accessLevel = accessLevel;
        this.schemaDatasource = schemaDatasource;
        this.businessDatasource = businessDatasource;
    }

    public String getEnvironment() { return environment; }
    public String getAccessLevel() { return accessLevel; }
    public ProjectDatasource getSchemaDatasource() { return schemaDatasource; }
    public ProjectDatasource getBusinessDatasource() { return businessDatasource; }
}
