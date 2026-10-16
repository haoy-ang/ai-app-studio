package com.haoy.aiapp.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 排序字段白名单校验器。
 *
 * <p>前端传来的 {@code sortField} 最终会被拼进 SQL 的 ORDER BY 子句。如果直接把字符串
 * 交给 MyBatis-Flex 的 {@code orderBy()}，攻击者就能传入任意 SQL 片段
 * （例如 {@code "id" ; DROP TABLE user ; --}）实现注入，属于典型的二阶注入入口。
 *
 * <p>这里用「显式白名单」代替一切字符串过滤：只有调用方声明允许排序的列名才会被放行，
 * 其余一律忽略并回退到默认排序。白名单是闭合集合，天然免疫拼接类注入。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public final class SortFieldValidator {

    private SortFieldValidator() {
    }

    /**
     * 校验排序字段是否合法。
     *
     * @param sortField     前端传入的排序字段，可为空
     * @param allowedFields 允许排序的列名白名单
     * @return 合法时返回该字段；不合法或为空时返回 {@code null}（调用方应回退默认排序）
     */
    public static String validate(String sortField, Set<String> allowedFields) {
        if (StrUtil.isBlank(sortField)) {
            return null;
        }
        if (CollUtil.isEmpty(allowedFields) || !allowedFields.contains(sortField)) {
            // 拦截而非抛异常：排序字段非法不应该让整个查询失败，回退默认排序即可
            log.warn("检测到非法的排序字段，已忽略。sortField={}", sortField);
            return null;
        }
        return sortField;
    }
}
