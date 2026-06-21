package com.tjc.bugagent.codegraph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * project_version 表索引状态字段的读写入口。
 * 把索引流程里的状态机持久化收在一处，让索引编排只管流程、不碰 JDBC。
 */
@Repository
public class IndexStatusRepository {

    private final JdbcTemplate jdbcTemplate;

    public IndexStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 进入索引中：重置开始时间和消息。 */
    public void markIndexing(Long versionId) {
        jdbcTemplate.update("update project_version set index_status = 'INDEXING', index_started_at = now(), index_message = null where id = ?", versionId);
    }

    /** 索引成功收尾。 */
    public void markSuccess(Long versionId) {
        jdbcTemplate.update("update project_version set index_status = 'SUCCESS', indexed_at = now(), index_message = 'index completed' where id = ?", versionId);
    }

    /** 索引失败，落具体错误信息。 */
    public void markFailed(Long versionId, String message) {
        jdbcTemplate.update("update project_version set index_status = 'FAILED', index_message = ? where id = ?", message, versionId);
    }

    /** 刷新当前进度消息。 */
    public void updateMessage(Long versionId, String message) {
        jdbcTemplate.update("update project_version set index_message = ? where id = ?", message, versionId);
    }
}
