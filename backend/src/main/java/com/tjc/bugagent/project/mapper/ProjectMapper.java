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

    /** 按名称/编码模糊查询（均可空），id 倒序 */
    List<Project> list(@Param("name") String name, @Param("code") String code);

    /** 按 id 查项目，无则 null */
    Project findById(@Param("id") Long id);

    /** 按编码查项目 */
    Project findByCode(@Param("code") String code);

    /** 统计同编码且非自身的项目数，用于编码唯一校验 */
    int countByCodeExcludingId(@Param("code") String code, @Param("id") Long id);

    /** 新增项目，回填自增主键到 id */
    void insert(Project project);

    /** 更新项目基础信息 */
    void update(@Param("id") Long id, @Param("name") String name,
                @Param("code") String code, @Param("description") String description);

    /** 删除项目 */
    void deleteById(@Param("id") Long id);
}
