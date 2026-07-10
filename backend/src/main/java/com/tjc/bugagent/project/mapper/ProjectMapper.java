package com.tjc.bugagent.project.mapper;

import com.tjc.bugagent.project.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * project 表的 MyBatis Mapper。SQL 见 resources/mapper/ProjectMapper.xml。
 */
@Mapper
public interface ProjectMapper {

    /** 按名称/编码模糊查询（均可空），id 倒序；ownerId 非空时只出该用户的项目（管理员传 null 看全部） */
    List<Project> list(@Param("name") String name, @Param("code") String code, @Param("ownerId") Long ownerId);

    /** 按 id 查项目，无则 null */
    Project findById(@Param("id") Long id);

    /** 只取归属用户，供权限校验用；项目不存在返回 null */
    Long findOwnerId(@Param("id") Long id);

    /** 统计同一所有者下同编码且非自身的项目数，用于编码唯一校验（唯一键为 owner_id + code） */
    int countByCodeExcludingId(@Param("code") String code, @Param("id") Long id, @Param("ownerId") Long ownerId);

    /** 新增项目，回填自增主键到 id */
    void insert(Project project);

    /** 更新项目基础信息 */
    void update(@Param("id") Long id, @Param("name") String name,
                @Param("code") String code, @Param("description") String description);

    /** 删除项目 */
    void deleteById(@Param("id") Long id);
}
