package com.haoy.aiapp.eval.inspect;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查器自检
 * <p>
 * <b>这个测试的作用不是「测业务」，而是「测尺子」。</b>
 * 后面所有评估结论都建立在这个检查器的判断之上：它说通过率 52%，谁信？
 * 只有当「人工认定为好的产物它判过、人工认定为坏的产物它判不过」时，那个 52% 才有意义。
 * <p>
 * 所以这里每个用例都是 <b>先给答案、再看它判得对不对</b>：fixture 是我手写的，
 * 里面埋了哪几个坑我一清二楚，检查器必须一个个把它们指出来。
 * <p>
 * 这套 fixture 还兼任「误报基线」：以后加新检查项时，如果 {@code html-good} 突然变红，
 * 说明新断言误报了，而不是 AI 退化了。
 *
 * @author <a>程序员haoy</a>
 */
class ArtifactInspectorTest {

    private static Path fixture(String name) {
        var url = Objects.requireNonNull(
                ArtifactInspectorTest.class.getResource("/eval/fixtures/" + name),
                "找不到 fixture: " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArtifactInspector.Inspection inspectFixture(String name, CodeGenTypeEnum type) {
        return ArtifactInspector.inspect(fixture(name), type);
    }

    /**
     * 断言指定的检查项在失败列表里
     */
    private static void assertFailedOn(ArtifactInspector.Inspection inspection, String checkName) {
        assertThat(inspection.failures())
                .as("期望 %s 失败，实际失败项: %s", checkName,
                        inspection.failures().stream().map(CheckResult::name).toList())
                .extracting(CheckResult::name)
                .contains(checkName);
    }

    @Nested
    @DisplayName("HTML 单页")
    class Html {

        @Test
        @DisplayName("合格页面：全部硬检查通过")
        void goodPagePasses() {
            var inspection = inspectFixture("html-good", CodeGenTypeEnum.HTML);

            assertThat(inspection.passed())
                    .as("好页面被误判为失败: %s", inspection.failures())
                    .isTrue();
            assertThat(inspection.warnings()).isEmpty();
        }

        @Test
        @DisplayName("坏页面：缺 body 骨架")
        void brokenPageFailsOnSkeleton() {
            assertFailedOn(inspectFixture("html-broken", CodeGenTypeEnum.HTML), "html-skeleton");
        }

        @Test
        @DisplayName("坏页面：TODO 残留")
        void brokenPageFailsOnResidue() {
            assertFailedOn(inspectFixture("html-broken", CodeGenTypeEnum.HTML), "no-template-residue");
        }

        @Test
        @DisplayName("坏页面：把 undefined 渲染到了页面上")
        void brokenPageFailsOnBrokenValue() {
            assertFailedOn(inspectFixture("html-broken", CodeGenTypeEnum.HTML), "no-broken-value");
        }

        @Test
        @DisplayName("坏页面：div 没闭合")
        void brokenPageFailsOnUnbalancedTags() {
            var inspection = inspectFixture("html-broken", CodeGenTypeEnum.HTML);

            assertFailedOn(inspection, "html-tags-balanced");
            assertThat(inspection.failures())
                    .filteredOn(c -> c.name().equals("html-tags-balanced"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.detail()).contains("div"));
        }

        @Test
        @DisplayName("坏页面：内容太少（疑似生成中断）")
        void brokenPageFailsOnSize() {
            assertFailedOn(inspectFixture("html-broken", CodeGenTypeEnum.HTML), "html-content-size");
        }

        @Test
        @DisplayName("Todo 应用不该被判成「TODO 残留」——这是真实产物扫出来的误报")
        void todoAppIsNotTreatedAsResidue() {
            var inspection = inspectFixture("html-todo-app", CodeGenTypeEnum.HTML);

            assertThat(inspection.passed())
                    .as("Todo 应用被误判: %s", inspection.failures())
                    .isTrue();
            assertThat(inspection.checks())
                    .filteredOn(c -> c.name().equals("no-template-residue"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.status()).isEqualTo(CheckResult.Status.PASS));
        }

        @Test
        @DisplayName("超大文件：内容检查要跳过，不能拿截断的一半文件下结论")
        void oversizedFileSkipsContentChecks(@TempDir Path temp) throws IOException {
            StringBuilder html = new StringBuilder("<html><head></head><body>");
            while (html.length() < 2 * 1024 * 1024 + 100) {
                html.append("<div>x</div>");
            }
            // 故意留一个没闭合的 div：如果检查器读的是截断内容，会误报「标签不配平」
            html.append("<div>");
            Files.writeString(temp.resolve("index.html"), html.toString(), StandardCharsets.UTF_8);

            var inspection = ArtifactInspector.inspect(temp, CodeGenTypeEnum.HTML);

            assertThat(inspection.checks())
                    .filteredOn(c -> c.name().equals("html-tags-balanced") || c.name().equals("html-has-visible-content"))
                    .hasSize(2)
                    .allSatisfy(c -> assertThat(c.status())
                            .as("%s 应该跳过而不是误判", c.name())
                            .isEqualTo(CheckResult.Status.SKIP));
        }
    }

    @Nested
    @DisplayName("多文件")
    class MultiFile {

        @Test
        @DisplayName("合格产物：引用的本地文件都在")
        void goodProjectPasses() {
            var inspection = inspectFixture("multi-good", CodeGenTypeEnum.MULTI_FILE);

            assertThat(inspection.passed())
                    .as("好产物被误判: %s", inspection.failures())
                    .isTrue();
            // 资源检查真的跑到了，不是 skip
            assertThat(inspection.checks())
                    .filteredOn(c -> c.name().equals("html-assets-exist"))
                    .singleElement()
                    .satisfies(c -> {
                        assertThat(c.status()).isEqualTo(CheckResult.Status.PASS);
                        assertThat(c.detail()).contains("2 个本地引用");
                    });
        }

        @Test
        @DisplayName("坏产物：引用了不存在的 css/js")
        void brokenProjectFailsOnMissingAssets() {
            var inspection = inspectFixture("multi-broken", CodeGenTypeEnum.MULTI_FILE);

            assertFailedOn(inspection, "html-assets-exist");
            assertThat(inspection.failures())
                    .filteredOn(c -> c.name().equals("html-assets-exist"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.detail())
                            .contains("missing-style.css")
                            .contains("missing-app.js"));
        }
    }

    @Nested
    @DisplayName("Vue 工程")
    class Vue {

        @Test
        @DisplayName("合格工程：结构与 import 都没问题（构建被跳过）")
        void goodProjectPasses() {
            var inspection = inspectFixture("vue-good", CodeGenTypeEnum.VUE_PROJECT);

            assertThat(inspection.passed())
                    .as("好工程被误判: %s", inspection.failures())
                    .isTrue();
            // 没有 node_modules 时构建必须报「跳过」而不是「通过」
            assertThat(inspection.skipped())
                    .extracting(CheckResult::name)
                    .contains("vue-build-passes");
        }

        @Test
        @DisplayName("坏工程：package.json 缺 vite")
        void brokenProjectFailsOnPackageJson() {
            assertFailedOn(inspectFixture("vue-broken", CodeGenTypeEnum.VUE_PROJECT), "vue-package-json");
        }

        @Test
        @DisplayName("坏工程：import 了不存在的组件与工具")
        void brokenProjectFailsOnUnresolvedImports() {
            var inspection = inspectFixture("vue-broken", CodeGenTypeEnum.VUE_PROJECT);

            assertFailedOn(inspection, "vue-imports-resolve");
            assertThat(inspection.failures())
                    .filteredOn(c -> c.name().equals("vue-imports-resolve"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.detail())
                            .contains("GhostWidget.vue")
                            // @/ 别名要按 src/ 解析 —— 这也是 build 失败的高频原因
                            .contains("format.js"));
        }

        @Test
        @DisplayName("没开构建选项时，构建项必须是 SKIP，不能算通过")
        void buildIsSkippedWhenOptionOff() {
            var inspection = ArtifactInspector.inspect(
                    fixture("vue-good"), CodeGenTypeEnum.VUE_PROJECT, ArtifactInspector.Options.staticOnly());

            assertThat(inspection.checks())
                    .filteredOn(c -> c.name().equals("vue-build-passes"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.status()).isEqualTo(CheckResult.Status.SKIP));
        }
    }

    @Nested
    @DisplayName("分发与容错")
    class Dispatch {

        @Test
        @DisplayName("按目录名推断生成类型")
        void infersTypeFromDirectoryName() {
            assertThat(ArtifactInspector.inferType(Path.of("tmp/code_output/html_123")))
                    .isEqualTo(CodeGenTypeEnum.HTML);
            assertThat(ArtifactInspector.inferType(Path.of("tmp/code_output/multi_file_9")))
                    .isEqualTo(CodeGenTypeEnum.MULTI_FILE);
            assertThat(ArtifactInspector.inferType(Path.of("tmp/code_output/vue_project_9")))
                    .isEqualTo(CodeGenTypeEnum.VUE_PROJECT);
            assertThat(ArtifactInspector.inferType(Path.of("tmp/code_output/whatever")))
                    .isNull();
        }

        @Test
        @DisplayName("目录不存在时不抛异常，而是把 root-exists 判失败")
        void missingDirectoryIsReportedNotThrown() {
            var inspection = ArtifactInspector.inspect(
                    Path.of("tmp/definitely-not-here-" + System.nanoTime()), CodeGenTypeEnum.HTML);

            assertThat(inspection.passed()).isFalse();
            assertFailedOn(inspection, "root-exists");
        }

        @Test
        @DisplayName("检查项数量与类型匹配（加断言时这里要同步）")
        void checkCountMatchesType() {
            assertThat(ArtifactInspector.checksFor(CodeGenTypeEnum.HTML)).hasSize(8);
            assertThat(ArtifactInspector.checksFor(CodeGenTypeEnum.MULTI_FILE))
                    .hasSize(ArtifactInspector.checksFor(CodeGenTypeEnum.HTML).size() + 1);
            assertThat(ArtifactInspector.checksFor(CodeGenTypeEnum.VUE_PROJECT)).hasSize(8);
        }
    }
}
