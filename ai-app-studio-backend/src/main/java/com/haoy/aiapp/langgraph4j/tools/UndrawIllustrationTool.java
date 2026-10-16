package com.haoy.aiapp.langgraph4j.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插画图片搜索工具 —— 从 unDraw 免费插画库取图，用于网站美化装饰
 * <p>
 * ⚠️ <b>该接口地址不能写死</b>：unDraw 的 buildId 随站点每次重新部署而变化，写死必然失效。
 * unDraw 是 Next.js 站点，它的数据接口形如
 * {@code https://undraw.co/_next/data/<buildId>/search/<term>.json?term=<term>}，
 * 其中 {@code buildId} 会随每次站点部署变化。写死的 {@code mMWmJSt23qpgo8cLTD_pB}
 * 实测返回 404。
 * <p>
 * 正确做法是两步走：
 * <ol>
 *   <li>先请求插画列表页，从 HTML 里正则取出<b>当前</b>的 buildId（进程内缓存，不用每次拉）</li>
 *   <li>再拼出数据接口地址</li>
 * </ol>
 * buildId 缓存失效时（站点重新部署了）会自动刷新一次并重试。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class UndrawIllustrationTool {

    private static final String UNDRAW_LIST_PAGE = "https://undraw.co/illustrations";

    private static final String UNDRAW_DATA_URL_TEMPLATE =
            "https://undraw.co/_next/data/%s/search/%s.json?term=%s";

    /**
     * Next.js 会把构建号内联在页面里，形如 "buildId":"uKihhkx-ImpA65QWSlQcw"
     */
    private static final Pattern BUILD_ID_PATTERN = Pattern.compile("\"buildId\":\"([^\"]+)\"");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * 缓存的 buildId —— 站点不重新部署就一直是有效的
     */
    private volatile String cachedBuildId;

    @Value("${image-search.search-count:12}")
    private int searchCount;

    /**
     * 搜索插画图片，用于网站美化和装饰
     *
     * @param query 搜索关键词
     * @return 插画资源列表；失败返回空列表（插画属于装饰，拿不到不应该阻塞主流程）
     */
    @Tool("搜索插画图片，用于网站美化和装饰")
    public List<ImageResource> searchIllustrations(@P("搜索关键词，建议用英文，例如 happy、teamwork、coding") String query) {
        if (StrUtil.isBlank(query)) {
            return new ArrayList<>();
        }
        // 第一次尝试：用当前缓存的 buildId
        List<ImageResource> result = doSearch(query, getBuildId());
        if (!result.isEmpty()) {
            return result;
        }
        // 第二次尝试：可能是站点重新部署导致 buildId 过期，强制刷新后再来一次
        log.info("unDraw 首次搜索无结果，刷新 buildId 后重试");
        cachedBuildId = null;
        String refreshed = getBuildId();
        return StrUtil.isBlank(refreshed) ? new ArrayList<>() : doSearch(query, refreshed);
    }

    /**
     * 取当前的 buildId（带进程内缓存）
     *
     * @return buildId；取不到返回 null
     */
    private String getBuildId() {
        String local = cachedBuildId;
        if (StrUtil.isNotBlank(local)) {
            return local;
        }
        synchronized (this) {
            if (StrUtil.isNotBlank(cachedBuildId)) {
                return cachedBuildId;
            }
            try (HttpResponse response = HttpRequest.get(UNDRAW_LIST_PAGE)
                    .header("User-Agent", USER_AGENT)
                    .timeout(20000)
                    .execute()) {
                if (!response.isOk()) {
                    log.warn("unDraw 列表页返回非 200：{}", response.getStatus());
                    return null;
                }
                Matcher matcher = BUILD_ID_PATTERN.matcher(response.body());
                if (!matcher.find()) {
                    log.warn("unDraw 列表页里没找到 buildId，站点结构可能又变了");
                    return null;
                }
                cachedBuildId = matcher.group(1);
                log.info("取到 unDraw 当前 buildId：{}", cachedBuildId);
                return cachedBuildId;
            } catch (Exception e) {
                log.warn("获取 unDraw buildId 失败：{}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * 按 buildId 拼出数据接口并解析插画列表
     */
    private List<ImageResource> doSearch(String query, String buildId) {
        List<ImageResource> imageList = new ArrayList<>();
        if (StrUtil.isBlank(buildId)) {
            return imageList;
        }
        String apiUrl = String.format(UNDRAW_DATA_URL_TEMPLATE, buildId, query, query);
        try (HttpResponse response = HttpRequest.get(apiUrl)
                .header("User-Agent", USER_AGENT)
                .timeout(20000)
                .execute()) {
            if (!response.isOk()) {
                // 403 通常是短时间请求过多被限流，不是代码问题
                log.warn("unDraw 数据接口返回 {}（403 表示被限流，稍后重试即可）", response.getStatus());
                return imageList;
            }
            JSONObject pageProps = JSONUtil.parseObj(response.body()).getJSONObject("pageProps");
            if (pageProps == null) {
                return imageList;
            }
            JSONArray initialResults = pageProps.getJSONArray("initialResults");
            if (initialResults == null || initialResults.isEmpty()) {
                return imageList;
            }
            int actualCount = Math.min(searchCount, initialResults.size());
            for (int i = 0; i < actualCount; i++) {
                JSONObject illustration = initialResults.getJSONObject(i);
                // media 是 CDN 上的直链（svg 格式，<img> 可以直接显示）
                String media = illustration.getStr("media", "");
                if (StrUtil.isBlank(media)) {
                    continue;
                }
                imageList.add(ImageResource.builder()
                        .category(ImageCategoryEnum.ILLUSTRATION)
                        .description(StrUtil.blankToDefault(illustration.getStr("title"), query))
                        .url(media)
                        .build());
            }
        } catch (Exception e) {
            log.warn("搜索 unDraw 插画失败：{}", e.getMessage());
        }
        return imageList;
    }
}
