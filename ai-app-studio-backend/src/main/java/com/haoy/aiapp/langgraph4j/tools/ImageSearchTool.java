package com.haoy.aiapp.langgraph4j.tools;

import cn.hutool.core.collection.CollUtil;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内容图片搜索工具 —— 把「关键词」变成一组可直接嵌入网页的图片 URL
 * <p>
 * <b>为什么不是单一图源？</b>
 * 最初用的是 Pexels，但免费图库 API 的可用性是会变的：
 * Pexels 已于 2026-10 暂停发放新 key，Openverse 在本机网络下完全不可达。
 * 如果把图源硬编码进去，任何一家出问题都得改代码。
 * 所以这里做成<b>可配置的降级链</b>：
 * <pre>
 *   有 key 的优先级图源（Pexels / Pixabay） → Wikimedia Commons（免 key） → Picsum（免 key）
 * </pre>
 * 前面的拿不到结果就自动往下走，保证<b>任何配置下都不会出现裂图</b>。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code image-search.provider} —— {@code auto}（默认，按上面顺序自动选）或指定图源名</li>
 *   <li>{@code image-search.search-count} —— 单次搜索取几张，默认 12</li>
 *   <li>{@code pexels.api-key} / {@code pixabay.api-key} —— 留空即自动跳过</li>
 * </ul>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class ImageSearchTool {

    private static final String PEXELS_API_URL = "https://api.pexels.com/v1/search";

    private static final String PIXABAY_API_URL = "https://pixabay.com/api/";

    private static final String WIKIMEDIA_API_URL = "https://commons.wikimedia.org/w/api.php";

    /**
     * Picsum 的地址本身就是图片，不需要调接口 —— 所以它是永远不会失败的最后兜底
     */
    private static final String PICSUM_URL_TEMPLATE = "https://picsum.photos/seed/%s-%d/800/600";

    /**
     * Wikimedia 要求请求方带上可识别的 User-Agent，否则可能被拒；
     * 其余图源也用同一个，避免个别接口对默认 UA 不友好。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * Wikimedia 可能混进非位图（SVG / PDF / DJVU），按扩展名过滤一轮
     */
    private static final List<String> BITMAP_EXTENSIONS =
            Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    @Value("${pexels.api-key:}")
    private String pexelsApiKey;

    @Value("${pixabay.api-key:}")
    private String pixabayApiKey;

    @Value("${image-search.provider:auto}")
    private String providerConfig;

    @Value("${image-search.search-count:12}")
    private int searchCount;

    /**
     * 搜索内容相关的图片，用于网站内容展示
     *
     * @param query 搜索关键词
     * @return 图片资源列表；即使所有图源都失败，也会返回兜底图源的占位图，不会返回空
     */
    @Tool("搜索内容相关的图片，用于网站内容展示")
    public List<ImageResource> searchContentImages(@P("搜索关键词，中英文均可，建议用最能代表网站主题的词") String query) {
        if (StrUtil.isBlank(query)) {
            return new ArrayList<>();
        }
        for (String provider : buildProviderChain()) {
            try {
                List<ImageResource> images = switch (provider) {
                    case "pexels" -> searchByPexels(query);
                    case "pixabay" -> searchByPixabay(query);
                    case "wikimedia" -> searchByWikimedia(query);
                    case "picsum" -> searchByPicsum(query);
                    default -> {
                        log.warn("未知的图源配置：{}，已跳过", provider);
                        yield new ArrayList<>();
                    }
                };
                if (CollUtil.isNotEmpty(images)) {
                    log.info("内容图片搜索成功，图源={}，关键词={}，数量={}", provider, query, images.size());
                    return images;
                }
                log.info("图源 {} 没搜到结果，继续尝试下一个", provider);
            } catch (Exception e) {
                // 单个图源失败不能影响整体：换下一个继续
                log.warn("图源 {} 搜索失败（将尝试下一个）：{}", provider, e.getMessage());
            }
        }
        log.warn("所有图源都没拿到图片，关键词={}", query);
        return new ArrayList<>();
    }

    /**
     * 组装图源降级链
     * <p>
     * {@code auto} 时按「需要 key 的优先（质量更好）→ 免 key 的兜底」排列；
     * 指定了具体图源时，把它提到最前面，其余仍作为兜底保留 —— 这样即使配错了也不会让整条链路失效。
     *
     * @return 去重后的图源名称列表
     */
    private List<String> buildProviderChain() {
        List<String> chain = new ArrayList<>();
        String configured = StrUtil.trimToEmpty(providerConfig).toLowerCase();
        if (!"auto".equals(configured) && StrUtil.isNotBlank(configured)) {
            chain.add(configured);
        }
        if (StrUtil.isNotBlank(pexelsApiKey)) {
            chain.add("pexels");
        }
        if (StrUtil.isNotBlank(pixabayApiKey)) {
            chain.add("pixabay");
        }
        chain.add("wikimedia");
        // Picsum 放最后：它只能给随机真实照片，和关键词无关，属于「有图总比裂图好」的兜底
        chain.add("picsum");
        Set<String> unique = new LinkedHashSet<>(chain);
        return new ArrayList<>(unique);
    }

    /**
     * Pexels 搜索（需要 key，200 次/小时。2026-10 起暂停发放新 key）
     */
    private List<ImageResource> searchByPexels(String query) {
        List<ImageResource> imageList = new ArrayList<>();
        // try-with-resources 确保 HTTP 连接被释放
        try (HttpResponse response = HttpRequest.get(PEXELS_API_URL)
                .header("Authorization", pexelsApiKey)
                .header("User-Agent", USER_AGENT)
                .form("query", query)
                .form("per_page", searchCount)
                .form("page", 1)
                .timeout(15000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("Pexels 返回非 200：{}", response.getStatus());
                return imageList;
            }
            JSONArray photos = JSONUtil.parseObj(response.body()).getJSONArray("photos");
            if (photos == null) {
                return imageList;
            }
            for (int i = 0; i < photos.size(); i++) {
                JSONObject photo = photos.getJSONObject(i);
                JSONObject src = photo.getJSONObject("src");
                if (src == null) {
                    continue;
                }
                imageList.add(ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description(photo.getStr("alt", query))
                        .url(src.getStr("medium", src.getStr("original")))
                        .build());
            }
        }
        return imageList;
    }

    /**
     * Pixabay 搜索（需要 key，注册后立即可用，100 次/分钟）
     * <p>
     * 注意：Pixabay 的条款不允许长期直接热链图片，本项目作为学习用途、且生成的是演示站点，
     * 直接使用其 URL 可接受；如果要正式上线，应当把图片下载后转存到自己的对象存储。
     */
    private List<ImageResource> searchByPixabay(String query) {
        List<ImageResource> imageList = new ArrayList<>();
        try (HttpResponse response = HttpRequest.get(PIXABAY_API_URL)
                .header("User-Agent", USER_AGENT)
                .form("key", pixabayApiKey)
                .form("q", query)
                .form("image_type", "photo")
                .form("safesearch", "true")
                .form("per_page", Math.min(searchCount, 200))
                .form("page", 1)
                .timeout(15000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("Pixabay 返回非 200：{}", response.getStatus());
                return imageList;
            }
            JSONArray hits = JSONUtil.parseObj(response.body()).getJSONArray("hits");
            if (hits == null) {
                return imageList;
            }
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                String url = hit.getStr("largeImageURL", hit.getStr("webformatURL"));
                if (StrUtil.isBlank(url)) {
                    continue;
                }
                imageList.add(ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description(StrUtil.blankToDefault(hit.getStr("tags"), query))
                        .url(url)
                        .build());
            }
        }
        return imageList;
    }

    /**
     * Wikimedia Commons 搜索（<b>完全不需要 key</b>，本机实测可用）
     * <p>
     * 用 {@code generator=search} + 命名空间 6（File 命名空间）来按关键词找文件，
     * 再用 {@code iiurlwidth} 让服务端直接返回缩略图地址 —— 原图动辄几十 MB，绝不能直接嵌进网页。
     */
    private List<ImageResource> searchByWikimedia(String query) {
        List<ImageResource> imageList = new ArrayList<>();
        // filetype:bitmap 过滤掉 SVG / PDF 等非位图，避免拿到浏览器不认的格式
        String searchKeyword = "filetype:bitmap " + query;
        try (HttpResponse response = HttpRequest.get(WIKIMEDIA_API_URL)
                .header("User-Agent", USER_AGENT)
                .form("action", "query")
                .form("generator", "search")
                .form("gsrsearch", searchKeyword)
                .form("gsrlimit", searchCount)
                .form("gsrnamespace", 6)
                .form("prop", "imageinfo")
                .form("iiprop", "url")
                .form("iiurlwidth", 800)
                .form("format", "json")
                .timeout(20000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("Wikimedia 返回非 200：{}", response.getStatus());
                return imageList;
            }
            JSONObject queryObj = JSONUtil.parseObj(response.body()).getJSONObject("query");
            if (queryObj == null) {
                return imageList;
            }
            JSONObject pages = queryObj.getJSONObject("pages");
            if (pages == null) {
                return imageList;
            }
            for (String pageId : pages.keySet()) {
                JSONObject page = pages.getJSONObject(pageId);
                JSONArray imageinfo = page.getJSONArray("imageinfo");
                if (imageinfo == null || imageinfo.isEmpty()) {
                    continue;
                }
                JSONObject info = imageinfo.getJSONObject(0);
                // 优先用服务端生成的缩略图，拿不到才退回原图
                String url = info.getStr("thumburl", info.getStr("url"));
                if (StrUtil.isBlank(url) || !isBitmapUrl(url)) {
                    continue;
                }
                // title 形如 "File:Science and Technology.jpg"，去掉前缀更适合写进提示词
                String title = StrUtil.removePrefix(page.getStr("title", ""), "File:");
                imageList.add(ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description(StrUtil.blankToDefault(title, query))
                        .url(url)
                        .build());
            }
        }
        return imageList;
    }

    /**
     * Picsum 兜底：<b>不调接口</b>，直接按 URL 规则生成随机真实照片
     * <p>
     * 它和图源关键词无关，只保证「页面不会出现裂图」。用 seed 固定随机种子，
     * 同一个关键词每次拿到同一批图，避免重新生成时图片全变。
     */
    private List<ImageResource> searchByPicsum(String query) {
        List<ImageResource> imageList = new ArrayList<>();
        int count = Math.max(1, Math.min(searchCount, 12));
        for (int i = 1; i <= count; i++) {
            imageList.add(ImageResource.builder()
                    .category(ImageCategoryEnum.CONTENT)
                    .description(query + " 相关配图（占位图源）")
                    .url(String.format(PICSUM_URL_TEMPLATE, StrUtil.trimToEmpty(query).replaceAll("[^\\w\\u4e00-\\u9fa5]", ""), i))
                    .build());
        }
        return imageList;
    }

    /**
     * 判断 Wikimedia 返回的是不是浏览器能直接渲染的位图
     */
    private boolean isBitmapUrl(String url) {
        String lower = url.toLowerCase();
        return BITMAP_EXTENSIONS.stream().anyMatch(lower::endsWith)
                // 缩略图地址形如 .../800px-Xxx.jpg，结尾仍是图片扩展名，上面的判断已覆盖
                || lower.contains(".jpg/") || lower.contains(".png/") || lower.contains(".jpeg/");
    }
}
