package com.tjc.bugagent.auth.mapper;

import com.tjc.bugagent.auth.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * users 表数据访问。列名下划线靠 map-underscore-to-camel-case 自动映射。
 */
@Mapper
public interface UserMapper {

    User findByUsername(@Param("username") String username);

    User findById(@Param("id") Long id);

    /** 列表不带 password_hash，避免哈希随响应外泄。 */
    List<User> listAll();

    void insert(User user);

    void updateProfile(@Param("id") Long id, @Param("role") String role,
                       @Param("status") String status, @Param("displayName") String displayName);

    void updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash,
                        @Param("mustChangePassword") boolean mustChangePassword);

    void touchLastLogin(@Param("id") Long id);

    int countAdmins();

    int countActiveAdminsExcept(@Param("id") Long id);
}
