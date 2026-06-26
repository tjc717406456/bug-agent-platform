package com.tjc.bugagent.codegraph;

import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.springframework.stereotype.Repository;

/**
 * project_version 表索引状态字段的读写入口。
 * 把索引流程里的状态机持久化收在一处，让索引编排只管流程、不碰 JDBC。
 */
@Repository
public class IndexStatusRepository {

    private final ProjectVersionMapper projectVersionMapper;

    public IndexStatusRepository(ProjectVersionMapper projectVersionMapper) {
        this.projectVersionMapper = projectVersionMapper;
    }

    /** 进入索引中：重置开始时间和消息。 */
    public void markIndexing(Long versionId) {
        projectVersionMapper.markIndexing(versionId);
    }

    /** 索引成功收尾。 */
    public void markSuccess(Long versionId) {
        projectVersionMapper.markSuccess(versionId);
    }

    /** 索引失败，落具体错误信息。 */
    public void markFailed(Long versionId, String message) {
        projectVersionMapper.markFailed(versionId, message);
    }

    /** 刷新当前进度消息。 */
    public void updateMessage(Long versionId, String message) {
        projectVersionMapper.updateMessage(versionId, message);
    }
}
