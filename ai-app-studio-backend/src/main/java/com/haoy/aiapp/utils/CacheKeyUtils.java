package com.haoy.aiapp.utils;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.model.dto.app.AppQueryRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存 key 生成工具类
 *
 * @author <a>程序员haoy</a>
 */
public class CacheKeyUtils {

    private CacheKeyUtils() {
    }

    /**
     * 根据对象生成缓存 key（JSON + MD5）
     * <p>
     * 两步走的理由：
     * <ol>
     *   <li><b>JSON 序列化</b> —— 保证「内容相同的对象 → 完全相同的字符串」，
     *       否则 toString 里带对象地址，每次算出来的 key 都不一样，缓存永远命中不了；</li>
     *   <li><b>MD5</b> —— 把可能很长的 JSON 压成 32 位定长字符串，避免 Redis key 过长。</li>
     * </ol>
     *
     * @param obj 要生成 key 的对象
     * @return MD5 哈希后的缓存 key
     */
    public static String generateKey(Object obj) {
        if (obj == null) {
            return DigestUtil.md5Hex("null");
        }
        String jsonStr = JSONUtil.toJsonStr(obj);
        return DigestUtil.md5Hex(jsonStr);
    }

    /**
     * 精选应用列表专用的缓存 key。
     * <p>
     * ⚠️ 为什么不直接 {@code generateKey(#appQueryRequest)}：
     * 缓存 key 是在 <b>方法体执行之前</b> 由 AOP 算出来的，而此时
     * {@code AppController.listGoodAppVOByPage} 里那句
     * {@code appQueryRequest.setPriority(GOOD_APP_PRIORITY)} 还没执行 ——
     * 也就是说用户随便传个 priority 就能得到一个全新的 key，把缓存整个绕过去。
     * 这里显式把 priority 排除掉。
     * <p>
     * ⚠️ 更重要的反面：其余字段 <b>必须全部保留</b>。
     * {@code AppQueryRequest} 会被整体交给 {@code getQueryWrapper}，
     * id / appName / cover / initPrompt / codeGenType / deployKey / userId / sortField / sortOrder
     * 全都会变成 SQL 条件 —— 少留一个字段，就会出现「不同的查询条件命中同一份缓存」，
     * 那是在返回错数据，比缓存命中率下降严重得多。
     *
     * @param req 分页查询请求
     * @return MD5 缓存 key
     */
    public static String goodAppPageKey(AppQueryRequest req) {
        if (req == null) {
            return generateKey("null");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", req.getId());
        normalized.put("appName", req.getAppName());
        normalized.put("cover", req.getCover());
        normalized.put("initPrompt", req.getInitPrompt());
        normalized.put("codeGenType", req.getCodeGenType());
        normalized.put("deployKey", req.getDeployKey());
        normalized.put("userId", req.getUserId());
        normalized.put("sortField", req.getSortField());
        normalized.put("sortOrder", req.getSortOrder());
        normalized.put("pageNum", req.getPageNum());
        normalized.put("pageSize", req.getPageSize());
        // 刻意不含 priority，理由见方法注释
        return generateKey(normalized);
    }
}
