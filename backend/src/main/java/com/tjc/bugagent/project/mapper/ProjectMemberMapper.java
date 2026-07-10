package com.tjc.bugagent.project.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * project_member 表：项目可见范围授权。管理员天然可见全部，不入表。
 * SQL 见 resources/mapper/ProjectMemberMapper.xml。
 */
@Mapper
public interface ProjectMemberMapper {

    /** 某项目已授权的用户 id 列表 */
    List<Long> listUserIds(@Param("projectId") Long projectId);

    /** 是否已授权，供权限校验用 */
    int countMember(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /** 清空某项目的全部授权（全量替换保存的前半步 / 删项目时级联） */
    void deleteByProject(@Param("projectId") Long projectId);

    /** 批量写入授权 */
    void insertAll(@Param("projectId") Long projectId, @Param("userIds") List<Long> userIds);
}
