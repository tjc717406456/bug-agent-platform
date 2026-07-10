package com.tjc.bugagent.auth;

import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 归属校验的纯逻辑测试：管理员通吃、他人项目一律 403、项目不存在也当越权处理
 * （否则能用状态码差异探测出别人项目的 id）。
 */
class ProjectAccessGuardTest {

    /** 只实现被 guard 用到的两个查询，其余方法用不到就不实现 */
    private final Map<Long, Long> projectOwners = new HashMap<Long, Long>();
    private final Map<Long, Long> recordProjects = new HashMap<Long, Long>();

    private final ProjectAccessGuard guard = new ProjectAccessGuard(
            (ProjectMapper) java.lang.reflect.Proxy.newProxyInstance(
                    ProjectMapper.class.getClassLoader(), new Class[]{ProjectMapper.class},
                    (proxy, method, args) -> "findOwnerId".equals(method.getName())
                            ? projectOwners.get((Long) args[0]) : null),
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

    @Test
    void ownerCanAccessOwnProject() {
        projectOwners.put(1L, 7L);
        loginAs(7L, "USER");
        assertDoesNotThrow(() -> guard.assertOwned(1L));
    }

    @Test
    void otherUserCannotAccessProject() {
        projectOwners.put(1L, 7L);
        loginAs(8L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertOwned(1L));
    }

    @Test
    void adminCanAccessAnyProject() {
        projectOwners.put(1L, 7L);
        loginAs(99L, "ADMIN");
        assertDoesNotThrow(() -> guard.assertOwned(1L));
    }

    /** 项目不存在与无权访问返回同一个 403，避免用响应差异枚举他人项目 */
    @Test
    void missingProjectIsForbiddenNotFound() {
        loginAs(7L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertOwned(404L));
    }

    @Test
    void recordOwnershipFollowsItsProject() {
        projectOwners.put(1L, 7L);
        recordProjects.put(50L, 1L);
        loginAs(7L, "USER");
        assertDoesNotThrow(() -> guard.assertRecordOwned(50L));
        loginAs(8L, "USER");
        assertThrows(ForbiddenException.class, () -> guard.assertRecordOwned(50L));
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

    /** ownerFilter 是「管理员看全部」的唯一开关：null 表示不加归属过滤 */
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
