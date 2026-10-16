package com.haoy.aiapp.langgraph4j.tools;

import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 图片工具契约测试 —— 不起 Spring 容器、不打网络，只钉住「容易静默出错」的纯逻辑。
 * <p>
 * 重点覆盖两件事：
 * <ol>
 *   <li><b>图源降级链的组装规则</b>。Pexels 已暂停发放新 key，整条链路能不能在「一个 key 都没有」
 *       的情况下仍然凑出可用图源（Wikimedia/Picsum），直接决定会不会出现裂图 —— 这是配置层逻辑，
 *       用反射把 {@code @Value} 字段塞进去就能测，不需要真发请求。</li>
 *   <li><b>几条「无输入 / 无渲染器」的短路分支</b>，确认它们返回空列表而不是抛异常
 *       （工作流里失败不能影响主流程）。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
class P2ImageToolsTest {

    // ---------- ImageSearchTool：图源降级链 ----------

    /**
     * 一个 key 都没有时，链里必须仍有 Wikimedia + Picsum —— 保证「零配置也能出图」。
     */
    @Test
    void providerChainShouldStillWorkWithoutAnyApiKey() throws Exception {
        ImageSearchTool tool = new ImageSearchTool();
        setField(tool, "pexelsApiKey", "");
        setField(tool, "pixabayApiKey", "");
        setField(tool, "providerConfig", "auto");

        List<String> chain = buildProviderChain(tool);
        assertEquals(List.of("wikimedia", "picsum"), chain);
    }

    /**
     * 配了 Pexels key 时，它应排在免 key 图源之前（质量更好优先），且免 key 图源仍保留作兜底。
     */
    @Test
    void providerChainShouldPutKeyedProvidersFirst() throws Exception {
        ImageSearchTool tool = new ImageSearchTool();
        setField(tool, "pexelsApiKey", "fake-pexels-key");
        setField(tool, "pixabayApiKey", "fake-pixabay-key");
        setField(tool, "providerConfig", "auto");

        List<String> chain = buildProviderChain(tool);
        assertEquals(List.of("pexels", "pixabay", "wikimedia", "picsum"), chain);
    }

    /**
     * 手动指定了某个图源时，它要被提到最前面，同时不能和后面的兜底项重复。
     */
    @Test
    void providerChainShouldDeduplicateConfiguredProvider() throws Exception {
        ImageSearchTool tool = new ImageSearchTool();
        setField(tool, "pexelsApiKey", "");
        setField(tool, "pixabayApiKey", "");
        setField(tool, "providerConfig", "picsum");

        List<String> chain = buildProviderChain(tool);
        assertEquals(List.of("picsum", "wikimedia"), chain);
        assertFalse(chain.contains(null), "链里不能混进 null 图源名");
    }

    /**
     * 只走 Picsum 时（不调接口、按 URL 规则产出），数量、分类、URL 规则都要对。
     */
    @Test
    void picsumFallbackShouldProduceUsableUrls() throws Exception {
        ImageSearchTool tool = new ImageSearchTool();
        setField(tool, "pexelsApiKey", "");
        setField(tool, "pixabayApiKey", "");
        setField(tool, "providerConfig", "picsum");
        setField(tool, "searchCount", 6);

        List<ImageResource> images = tool.searchContentImages("technology blog");
        assertEquals(6, images.size());
        for (ImageResource image : images) {
            assertEquals(ImageCategoryEnum.CONTENT, image.getCategory());
            assertTrue(image.getUrl().startsWith("https://picsum.photos/seed/"),
                    "Picsum 地址必须走 seed 规则，实得：" + image.getUrl());
        }
    }

    /**
     * 空关键词直接短路返回空列表，不应该去拼链、更不应该发请求。
     */
    @Test
    void blankQueryShouldShortCircuit() {
        assertTrue(new ImageSearchTool().searchContentImages("  ").isEmpty());
        assertTrue(new UndrawIllustrationTool().searchIllustrations("").isEmpty());
        assertTrue(new LogoGeneratorTool().generateLogos("   ").isEmpty());
    }

    // ---------- UndrawIllustrationTool：buildId 提取 ----------

    /**
     * unDraw 已换成动态 buildId，整条修复就靠这个正则。
     * 它一旦失效，表现是「插画永远搜不到」—— 静默失败，所以必须钉住。
     */
    @Test
    void buildIdPatternShouldExtractFromNextJsHtml() throws Exception {
        Field field = UndrawIllustrationTool.class.getDeclaredField("BUILD_ID_PATTERN");
        field.setAccessible(true);
        Pattern pattern = (Pattern) field.get(null);
        assertNotNull(pattern);

        // 模拟 Next.js 页面里内联的构建号
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"buildId\":\"uKihhkx-ImpA65QWSlQcw\",\"props\":{}}</script>";
        Matcher matcher = pattern.matcher(html);
        assertTrue(matcher.find(), "正则必须能从页面里找到 buildId");
        assertEquals("uKihhkx-ImpA65QWSlQcw", matcher.group(1));

        // 站点结构变了（没有 buildId）时必须老实返回 false，不能匹配到空串
        assertFalse(pattern.matcher("<html><body>nothing here</body></html>").find());
    }

    // ---------- MermaidDiagramTool：无渲染器时短路 ----------

    /**
     * 既没配 mmdc、又关掉在线兜底时，必须安静返回空列表（架构图属于增强内容，失败不阻塞主流程），
     * 而且不能走到 COS 上传（cosManager 为 null，一碰就 NPE）。
     */
    @Test
    void mermaidShouldReturnEmptyWhenNoRendererAvailable() throws Exception {
        MermaidDiagramTool tool = new MermaidDiagramTool();
        setField(tool, "mmdcCommand", "");
        setField(tool, "inkFallback", false);

        List<ImageResource> result = tool.generateArchitectureDiagram("flowchart LR\n A-->B", "架构图");
        assertTrue(result.isEmpty(), "没有任何渲染器时应返回空列表而不是抛异常");
    }

    /**
     * 空 Mermaid 代码直接短路，不进入渲染流程。
     */
    @Test
    void mermaidShouldReturnEmptyForBlankCode() {
        assertTrue(new MermaidDiagramTool().generateArchitectureDiagram("", "架构图").isEmpty());
    }

    // ---------- 反射小工具 ----------

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> buildProviderChain(ImageSearchTool tool) throws Exception {
        Method method = ImageSearchTool.class.getDeclaredMethod("buildProviderChain");
        method.setAccessible(true);
        return (List<String>) method.invoke(tool);
    }
}
