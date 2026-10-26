package com.oj.judge.memory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oj.common.auth.annotation.RoleCheck;
import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.auth.constant.UserConstant;

/**
 * 「非管理员拒绝」这条验收在**框架层**落实：`@RoleCheck(mustRole=ADMIN)`。
 *
 * <p>为什么用反射测：真正的拦截发生在 Spring MVC 的切面里，纯对象单测没有请求上下文，
 * 起 `@SpringBootTest` 又要连 Nacos（本机 3 个用例就是这么坏掉的）。这里锁住的是
 * 「注解还在、角色还是 ADMIN」——有人手滑删掉注解或改成别的角色，这条会红。
 */
class SkillControllerAuthTest {

    private static final List<String> ADMIN_ONLY = List.of(
            "create", "extract", "review", "deprecate", "promote", "candidates");

    private static Method method(String name) {
        return Arrays.stream(SkillController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing endpoint: " + name));
    }

    @Test
    void everyLifecycleEndpointIsAdminOnlyAndTokenChecked() {
        for (String name : ADMIN_ONLY) {
            Method method = method(name);
            assertEquals(UserConstant.ADMIN_ROLE, method.getAnnotation(RoleCheck.class).mustRole(),
                    name + " must stay admin-only");
            assertNotNull(method.getAnnotation(TokenCheck.class), name + " must require a token");
        }
    }

    @Test
    void promotionHasBothManualAndCandidateEntryPoints() {
        assertNotNull(method("promote"));
        assertNotNull(method("candidates"));
    }

    @Test
    void readingYourOwnSkillsNeedsALoginButNotAdmin() {
        // 列表与提交是普通用户动作：只有 @TokenCheck，没有 @RoleCheck。
        for (String name : List.of("list", "submit", "used")) {
            Method method = method(name);
            assertNotNull(method.getAnnotation(TokenCheck.class), name + " must require a token");
            assertEquals(null, method.getAnnotation(RoleCheck.class), name + " must not require admin");
        }
    }
}
