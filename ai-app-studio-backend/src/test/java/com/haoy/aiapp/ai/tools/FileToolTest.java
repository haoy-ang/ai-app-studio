package com.haoy.aiapp.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.haoy.aiapp.constant.AppConstant;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件工具集测试
 * <p>
 * 「可视化修改」的五个工具都是直接操作磁盘的，出错就是改坏用户的项目，
 * 所以这里重点覆盖两类路径：<b>正常读写</b> 和 <b>不该被放行的危险操作</b>。
 * 后者是早期版本里缺的部分（绝对路径放行、路径穿越），属于回归测试。
 * <p>
 * 测试不需要 Spring 容器：工具类本身无状态，直接 new 出来即可，
 * 唯一的依赖 appId 由 {@code @ToolMemoryId} 在真实调用时注入，测试里手动传。
 *
 * @author <a>程序员haoy</a>
 */
class FileToolTest {

    /**
     * 专供测试使用的 appId，避开真实应用的目录
     */
    private static final long TEST_APP_ID = 990000001L;

    private final FileWriteTool fileWriteTool = new FileWriteTool();
    private final FileReadTool fileReadTool = new FileReadTool();
    private final FileModifyTool fileModifyTool = new FileModifyTool();
    private final FileDirReadTool fileDirReadTool = new FileDirReadTool();
    private final FileDeleteTool fileDeleteTool = new FileDeleteTool();

    private Path projectRoot;

    @BeforeEach
    void setUp() throws Exception {
        // 目录结构与真实生成结果保持一致：{CODE_OUTPUT_ROOT_DIR}/vue_project_{appId}
        projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                AppConstant.VUE_PROJECT_DIR_PREFIX + TEST_APP_ID);
        FileUtil.del(projectRoot.toFile());
        Files.createDirectories(projectRoot);
    }

    @AfterEach
    void tearDown() {
        // 只删本次测试造出来的那个项目目录，不动 code_output 下的其他应用
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    @DisplayName("写入后能原样读回，中文不会变乱码")
    void testWriteThenRead() {
        String content = """
                <template>
                  <h1>个人简历</h1>
                  <p>某某大学艺术设计专业，学士学位</p>
                </template>
                """;
        String writeResult = fileWriteTool.writeFile("src/pages/Resume.vue", content, TEST_APP_ID);
        assertTrue(writeResult.contains("成功"), "写入应成功，实际: " + writeResult);

        String readResult = fileReadTool.readFile("src/pages/Resume.vue", TEST_APP_ID);
        assertEquals(content, readResult, "读回的内容应与写入完全一致（含中文）");
    }

    @Test
    @DisplayName("写入工具会自动创建不存在的父目录")
    void testWriteCreatesParentDirs() {
        fileWriteTool.writeFile("src/components/deep/Hello.vue", "<template></template>", TEST_APP_ID);
        assertTrue(Files.exists(projectRoot.resolve("src/components/deep/Hello.vue")));
    }

    @Test
    @DisplayName("读取目录会忽略 node_modules / dist / .env，并返回可直接使用的相对路径")
    void testReadDirStructure() throws Exception {
        fileWriteTool.writeFile("index.html", "<html></html>", TEST_APP_ID);
        fileWriteTool.writeFile("src/pages/Resume.vue", "<template></template>", TEST_APP_ID);
        fileWriteTool.writeFile("node_modules/vue/index.js", "// 依赖", TEST_APP_ID);
        fileWriteTool.writeFile("dist/index.html", "<html>产物</html>", TEST_APP_ID);
        fileWriteTool.writeFile(".env", "SECRET=1", TEST_APP_ID);
        fileWriteTool.writeFile("build.log", "噪音", TEST_APP_ID);

        String structure = fileDirReadTool.readDir("", TEST_APP_ID);

        assertTrue(structure.contains("src/pages/Resume.vue"),
                "应返回相对项目根目录的路径（AI 可直接拿去读文件），实际:\n" + structure);
        assertFalse(structure.contains("node_modules"), "node_modules 必须被剪掉");
        assertFalse(structure.contains("dist/"), "dist 必须被忽略");
        assertFalse(structure.contains(".env"), ".env 必须被忽略");
        assertFalse(structure.contains("build.log"), ".log 必须被忽略");
    }

    @Test
    @DisplayName("修改工具按旧片段做精确替换，其他内容不受影响")
    void testModifyFile() {
        fileWriteTool.writeFile("src/pages/Resume.vue",
                "<p>某某大学艺术设计专业，学士学位</p>\n<p>某某设计公司，高级设计师</p>", TEST_APP_ID);

        String result = fileModifyTool.modifyFile("src/pages/Resume.vue",
                "<p>某某大学艺术设计专业，学士学位</p>",
                "<p>清华大学美术学院视觉传达设计专业，硕士学位</p>",
                TEST_APP_ID);
        assertTrue(result.contains("成功"), "修改应成功，实际: " + result);

        String content = fileReadTool.readFile("src/pages/Resume.vue", TEST_APP_ID);
        assertTrue(content.contains("清华大学美术学院视觉传达设计专业，硕士学位"));
        assertTrue(content.contains("某某设计公司，高级设计师"), "未被要求修改的内容必须保持原样");
    }

    @Test
    @DisplayName("旧片段不存在时只警告、绝不改文件")
    void testModifyFileWhenOldContentMissing() {
        String origin = "<p>原文案</p>";
        fileWriteTool.writeFile("src/pages/Resume.vue", origin, TEST_APP_ID);

        String result = fileModifyTool.modifyFile("src/pages/Resume.vue",
                "<p>文件里根本没有的片段</p>", "<p>新文案</p>", TEST_APP_ID);

        assertTrue(result.startsWith("警告"), "应返回警告，实际: " + result);
        assertEquals(origin, fileReadTool.readFile("src/pages/Resume.vue", TEST_APP_ID),
                "文件内容必须原封不动——这里写错会把整个文件内容替换掉");
    }

    @Test
    @DisplayName("删除工具能正常删除普通文件")
    void testDeleteFile() {
        fileWriteTool.writeFile("src/pages/Old.vue", "<template></template>", TEST_APP_ID);

        String result = fileDeleteTool.deleteFile("src/pages/Old.vue", TEST_APP_ID);

        assertTrue(result.contains("成功"), "删除应成功，实际: " + result);
        assertFalse(Files.exists(projectRoot.resolve("src/pages/Old.vue")));
    }

    @Test
    @DisplayName("骨架文件受保护，删不掉")
    void testDeleteProtectedFileRejected() {
        fileWriteTool.writeFile("package.json", "{\"name\":\"demo\"}", TEST_APP_ID);

        String result = fileDeleteTool.deleteFile("package.json", TEST_APP_ID);

        assertTrue(result.startsWith("错误"), "应拒绝删除，实际: " + result);
        assertTrue(Files.exists(projectRoot.resolve("package.json")), "package.json 必须还在");
    }

    @Test
    @DisplayName("路径穿越被拒绝：../../ 无法跑出项目目录")
    void testPathTraversalRejected() {
        String outsidePath = "src/../../../evil.txt";
        fileWriteTool.writeFile("src/pages/Resume.vue", "内容", TEST_APP_ID);

        String readResult = fileReadTool.readFile(outsidePath, TEST_APP_ID);
        String deleteResult = fileDeleteTool.deleteFile(outsidePath, TEST_APP_ID);
        String modifyResult = fileModifyTool.modifyFile(outsidePath, "内容", "改", TEST_APP_ID);

        assertTrue(readResult.startsWith("错误"), "读越界应被拒绝，实际: " + readResult);
        assertTrue(deleteResult.startsWith("错误"), "删越界应被拒绝，实际: " + deleteResult);
        assertTrue(modifyResult.startsWith("错误"), "改越界应被拒绝，实际: " + modifyResult);
    }

    @Test
    @DisplayName("绝对路径被拒绝，不能读写项目目录以外的文件")
    void testAbsolutePathRejected() {
        // 取 code_output 的上一级，一定是项目目录之外
        String outsideAbsolutePath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR)
                .getParent().resolve("evil.txt").toString();

        String result = fileReadTool.readFile(outsideAbsolutePath, TEST_APP_ID);

        assertTrue(result.startsWith("错误"), "绝对路径应被拒绝，实际: " + result);
    }

    @Test
    @DisplayName("appId 缺失时拒绝执行（守住 @ToolMemoryId 标错就写到乱编目录的坑）")
    void testMissingAppIdRejected() {
        String result = fileReadTool.readFile("index.html", null);

        assertTrue(result.startsWith("错误"), "缺少 appId 应被拒绝，实际: " + result);
        assertTrue(result.contains("appId"));
    }

    @Test
    @DisplayName("工具管理器能按名字取到全部 5 个工具")
    void testToolManagerRegistration() {
        ToolManager toolManager = buildToolManager();

        assertEquals(5, toolManager.getAllTools().length, "应注册 5 个工具");
        assertNotNull(toolManager.getTool("writeFile"));
        assertNotNull(toolManager.getTool("readFile"));
        assertNotNull(toolManager.getTool("readDir"));
        assertNotNull(toolManager.getTool("modifyFile"));
        assertNotNull(toolManager.getTool("deleteFile"));
        // 工具名必须与 @Tool 方法名严格一致，否则流处理查不到工具、输出直接空白
        assertEquals("writeFile", toolManager.getTool("writeFile").getToolName());
        assertEquals("写入文件", toolManager.getTool("writeFile").getDisplayName());
    }

    @Test
    @DisplayName("LangChain4j 实际暴露给 AI 的工具清单必须与 getToolName() 完全对齐")
    void testToolSpecificationsMatchToolNames() {
        ToolManager toolManager = buildToolManager();

        // 工厂里写的是 .tools((Object[]) toolManager.getAllTools())，靠的是「数组被摊开成多个工具」。
        // 一旦这里改成返回 List，泛型不变性会让它匹配不上 tools(Collection<Object>)，
        // 退化成「把整个 List 当成一个工具对象」——编译、启动、构建全都正常，
        // 只是 AI 一个工具都调不到，属于最难排查的一类故障。所以这条断言要钉住。
        assertTrue(toolManager.getAllTools().getClass().isArray(), "getAllTools() 必须返回数组");

        Set<String> exposedToolNames = new HashSet<>();
        for (BaseTool tool : toolManager.getAllTools()) {
            List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(tool);
            // 一个工具类只该有 1 个 @Tool 方法：多出来会让 getToolName() 只能对上其中一个
            assertEquals(1, specifications.size(), tool.getClass().getSimpleName()
                    + " 应该只暴露 1 个 @Tool 方法，实际暴露: " + specifications.size());
            String specificationName = specifications.get(0).name();
            assertEquals(tool.getToolName(), specificationName,
                    "getToolName() 必须与 @Tool 方法名一致，否则流处理按名字查不到工具");
            exposedToolNames.add(specificationName);
        }
        // 这正是 AI 能看到的 5 个工具名
        assertEquals(Set.of("writeFile", "readFile", "readDir", "modifyFile", "deleteFile"),
                exposedToolNames);
    }

    /**
     * 手动装配一个 ToolManager，模拟 Spring 把 BaseTool 数组注入进来的效果
     */
    private ToolManager buildToolManager() {
        ToolManager toolManager = new ToolManager();
        ReflectionTestUtils.setField(toolManager, "tools", new BaseTool[]{
                fileWriteTool, fileReadTool, fileDirReadTool, fileModifyTool, fileDeleteTool
        });
        toolManager.initTools();
        return toolManager;
    }

    @Test
    @DisplayName("修改工具的输出同时展示替换前后的内容")
    void testModifyToolDisplay() {
        String display = fileModifyTool.generateToolExecutedResult(
                cn.hutool.json.JSONUtil.parseObj(
                        "{\"relativeFilePath\":\"src/App.vue\",\"oldContent\":\"旧\",\"newContent\":\"新\"}"));

        assertTrue(display.contains("替换前"), "应展示替换前内容，实际: " + display);
        assertTrue(display.contains("替换后"), "应展示替换后内容，实际: " + display);
        assertTrue(display.contains("旧") && display.contains("新"));
    }

    @Test
    @DisplayName("写入的文件用 UTF-8 落盘")
    void testFileEncodingIsUtf8() throws Exception {
        fileWriteTool.writeFile("src/pages/Chinese.vue", "中文内容测试", TEST_APP_ID);

        byte[] raw = Files.readAllBytes(projectRoot.resolve("src/pages/Chinese.vue"));
        assertEquals("中文内容测试", new String(raw, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("内容一模一样的重复写入会被跳过——这是「工具调用死循环」的确定性刹车")
    void testWriteSameContentTwiceIsSkipped() {
        String content = "<template><h1>首页</h1></template>";
        String first = fileWriteTool.writeFile("src/pages/Home.vue", content, TEST_APP_ID);
        assertTrue(first.contains("成功"), "第一次写入应成功，实际: " + first);

        // 背景：Vue 工具调用生成时，模型会陷进「一轮一轮重写同一批文件」的死循环
        // （实测连续 19 分钟写了 196 次、SSE 永不结束）。内容没变就不落盘、
        // 并把「没有变化」明确回给模型，让它有机会意识到该收尾了。
        String second = fileWriteTool.writeFile("src/pages/Home.vue", content, TEST_APP_ID);
        assertTrue(second.contains("跳过"), "内容未变化的重复写入应被跳过，实际: " + second);
        assertEquals(content, fileReadTool.readFile("src/pages/Home.vue", TEST_APP_ID));

        // 但内容真的变了必须正常写入，否则可视化修改就没法用了
        String third = fileWriteTool.writeFile("src/pages/Home.vue", content + "\n<!-- 改过了 -->", TEST_APP_ID);
        assertTrue(third.contains("成功"), "内容有变化时必须正常写入，实际: " + third);
        assertTrue(fileReadTool.readFile("src/pages/Home.vue", TEST_APP_ID).contains("改过了"));
    }
}
