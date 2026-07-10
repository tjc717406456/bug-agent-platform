package com.tjc.bugagent.auth;

import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import com.tjc.bugagent.project.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 访问校验的纯逻辑测试：管理员通吃、所有者与被授权成员可访问、其他人一律 403、
 * 项目不存在也当越权处理（否则能用状态码差异探测出别人项目的 id）。
 */
class ProjectAccessGuardTest {

    /** 只实现被 guard 用到的三个查询，其余方法用不到就不实现 */
    private final Map<Long, Long> projectOwners = new HashMap<Long, Long>();
    private final Map<Long, Long> recordProjects = new HashMap<Long, Long>();
    private final Set<String> members = new HashSet<String>();

    private final ProjectAccessGuard guard = new ProjectAccessGuard(
            (ProjectMapper) java.lang.reflect.Proxy.newProxyInstance(
                    ProjectMapper.class.getClassLoader(), new Class[]{ProjectMapper.class},
                    (proxy, method, args) -> "findOwnerId".equals(method.getName())
                            ? projectOwners.get((Long) args[0]) : null),
            (ProjectMemberMapper) java.lang.reflect.Proxy.newProxyInstance(
                    ProjectMemberMapper.class.getClassLoader(), new Class[]{ProjectMemberMapper.class},
                    (proxy, method, args) -> "countMember".equals(method.getName())
                            ? (members.contains(args[0] + ":" + args[1]) ? 1 : 0) : null),
            (AnalysisRecordMapper) java.lang.reflect.Proxy.newProxyInstance(
                    AnalysisRecordMapper.class.getClassLoader(), new Class[]{AnalysisRecordMapper.class},
                    (proxy, method, args) -> "findProjectId".equals(method.getName())
                            ? recordProjects.get((Long) args[0]) : null));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAs(long userId, String role) {
        UserContext.set(new CurrentUser(userId, "u" + userId, role));
    }

    private void grant(long projectId, long userId) {
        members.add(projectId + ":" + userId);
    }

    @Test
    void ownerCanAccessOwnProject() {
        projectOwners.put(1L, 7L);
        loginAs(7L, "USER");
        assertDoesNotThrow(() -> guard.assertCanAccess(1L));
    }

    @Test
    void grantedMemberCanAccessProject() {
        projectOwners.put(1L, 7L);
        grant(1L, 8L);
        loginAs(8L, "USER");
        assertDoesNotThrow(() -> guard.assertCanAccess(1L));
    }

    @Test
    void otherUserCannotAccessProject() {
        projectOwners.put(1L, 7L);
        loginAs(8L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertCanAccess(1L));
    }

    @Test
    void adminCanAccessAnyProject() {
        projectOwners.put(1L, 7L);
        loginAs(99L, "ADMIN");
        assertDoesNotThrow(() -> guard.assertCanAccess(1L));
    }

    /** 项目不存在与无权访问返回同一个 403，避免用响应差异枚举他人项目 */
    @Test
    void missingProjectIsForbiddenNotFound() {
        loginAs(7L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertCanAccess(404L));
    }

    /** 记录跟随项目判权：所有者与被授权成员共享历史，外人 403 */
    @Test
    void recordAccessFollowsItsProject() {
        projectOwners.put(1L, 7L);
        recordProjects.put(50L, 1L);
        grant(1L, 8L);
        loginAs(7L, "USER");
        assertDoesNotThrow(() -> guard.assertRecordAccessible(50L));
        loginAs(8L, "USER");
        assertDoesNotThrow(() -> guard.assertRecordAccessible(50L));
        loginAs(9L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertRecordAccessible(50L));
    }

    @Test
    void taskOwnershipChecksSubmitter() {
        loginAs(7L, "USER");
        assertDoesNotThrow(() -> guard.assertTaskOwned(7L));
        assertThrows(ForbiddenException.class, () -> guard.assertTaskOwned(8L));
        // 老任务没盖章（ownerId 为空）也不能被别人碰
        assertThrows(ForbiddenException.class, () -> guard.assertTaskOwned(null));
        loginAs(99L, "ADMIN");
        assertDoesNotThrow(() -> guard.assertTaskOwned(8L));
    }

    /** ownerFilter 是「管理员看全部」的唯一开关：null 表示不加可见性过滤 */
    @Test
    void ownerFilterIsNullForAdminOnly() {
        loginAs(7L, "USER");
        assertEquals(7L, guard.ownerFilter());
        loginAs(99L, "ADMIN");
        assertNull(guard.ownerFilter());
    }

    @Test
    void adminOnlyOperationsRejectNormalUser() {
        loginAs(7L, "USER");
        assertThrows(ForbiddenException.class, guard::assertAdmin);
        loginAs(99L, "ADMIN");
        assertDoesNotThrow(guard::assertAdmin);
    }
}
