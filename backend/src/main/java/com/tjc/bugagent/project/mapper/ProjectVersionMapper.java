package com.tjc.bugagent.project.mapper;

import com.tjc.bugagent.project.ProjectVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * project_version 表的 MyBatis Mapper。SQL 见 resources/mapper/ProjectVersionMapper.xml。
 * 被 ProjectService 与 IndexStatusRepository 共享：前者管版本增删查、后者管索引状态更新。
 */
@Mapper
public interface ProjectVersionMapper {

    /** 新增版本，初始 PENDING，自增 id 回填到入参 version.id */
    void insertVersion(ProjectVersion version);

    /** 项目下所有版本，id 倒序 */
    List<ProjectVersion> listByProject(@Param("projectId") Long projectId);

    /** 项目下最新一个索引成功的版本，无则 null */
    ProjectVersion latestReady(@Param("projectId") Long projectId);

    /** 按 id 查版本 */
    ProjectVersion findById(@Param("id") Long id);

    /** 取版本源码路径（按 id+项目限定） */
    List<String> findSourcePaths(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 删项目下全部版本 */
    void deleteByProject(@Param("projectId") Long projectId);

    /** 按 id+项目删单个版本 */
    void deleteByIdAndProject(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 进入索引中：重置开始时间和消息 */
    void markIndexing(@Param("id") Long id);

    /** 索引成功收尾 */
    void markSuccess(@Param("id") Long id);

    /** 索引失败，落错误信息 */
    void markFailed(@Param("id") Long id, @Param("message") String message);

    /** 刷新当前进度消息 */
    void updateMessage(@Param("id") Long id, @Param("message") String message);

    /** ZIP 解压完成后更新实际源码根目录。 */
    void updateSourcePath(@Param("id") Long id, @Param("sourcePath") String sourcePath);
}
