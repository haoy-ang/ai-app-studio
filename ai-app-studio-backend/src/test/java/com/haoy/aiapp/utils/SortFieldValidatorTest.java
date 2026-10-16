package com.haoy.aiapp.utils;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 排序字段白名单校验器测试。
 *
 * <p>这个类的存在意义就是「防 SQL 注入」，所以测试重点不是正常路径，
 * 而是「恶意输入必须被拦下」。
 *
 * @author <a>程序员haoy</a>
 */
class SortFieldValidatorTest {

    private static final Set<String> ALLOWED = Set.of("id", "appName", "createTime");

    @Test
    void shouldPassWhitelistedField() {
        assertEquals("createTime", SortFieldValidator.validate("createTime", ALLOWED));
        assertEquals("id", SortFieldValidator.validate("id", ALLOWED));
    }

    @Test
    void shouldRejectFieldOutsideWhitelist() {
        // 白名单是精确匹配：真实存在但不在白名单里的列名同样拒绝
        assertNull(SortFieldValidator.validate("userPassword", ALLOWED));
    }

    @Test
    void shouldRejectSqlInjectionPayload() {
        assertNull(SortFieldValidator.validate("id; DROP TABLE user; --", ALLOWED));
        assertNull(SortFieldValidator.validate("id) --", ALLOWED));
        assertNull(SortFieldValidator.validate("1=1", ALLOWED));
        // 大小写不匹配也按非法处理
        assertNull(SortFieldValidator.validate("CreateTime", ALLOWED));
    }

    @Test
    void shouldRejectBlankOrNullInput() {
        assertNull(SortFieldValidator.validate(null, ALLOWED));
        assertNull(SortFieldValidator.validate("", ALLOWED));
        assertNull(SortFieldValidator.validate("   ", ALLOWED));
    }

    @Test
    void shouldReturnNullWhenWhitelistIsEmpty() {
        assertNull(SortFieldValidator.validate("id", Set.of()));
        assertNull(SortFieldValidator.validate("id", null));
    }
}
