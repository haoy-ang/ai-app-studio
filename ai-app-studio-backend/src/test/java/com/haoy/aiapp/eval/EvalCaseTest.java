package com.haoy.aiapp.eval;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测用例的解析与加载
 * <p>
 * <b>为什么这件事值得单独测？</b>因为用例写错时最危险的失败方式是「静默跳过」——
 * 字段名拼错、类型写成 {@code "HTML 单页"} 而不是 {@code "html"}，如果解析器不吭声，
 * 你会以为跑了 24 条，其实只跑了 23 条，而通过率的分母悄悄变了。
 * <p>
 * 所以这里的原则是：<b>宁可启动时崩，不要跑到一半发现用例少了。</b>
 *
 * @author <a>程序员haoy</a>
 */
class EvalCaseTest {

    @Test
    @DisplayName("从目录加载：能读出示例用例的全部字段")
    void loadsFromDirectory() {
        var url = Objects.requireNonNull(getClass().getResource("/eval/cases"));
        Path dir;
        try {
            dir = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }

        List<EvalCase> cases = EvalCase.loadAll(dir);

        assertThat(cases).hasSize(1);
        EvalCase evalCase = cases.get(0);
        assertThat(evalCase.id()).isEqualTo("example-html-landing");
        assertThat(evalCase.expectedType()).isEqualTo(CodeGenTypeEnum.HTML);
        assertThat(evalCase.mustInclude()).contains("<nav", "<footer");
        assertThat(evalCase.mustNotInclude()).contains("TODO");
        assertThat(evalCase.prompt()).isNotBlank();
    }

    @Test
    @DisplayName("目录不存在时返回空列表，不抛异常")
    void missingDirectoryYieldsEmptyList() {
        assertThat(EvalCase.loadAll(Path.of("does/not/exist"))).isEmpty();
        assertThat(EvalCase.loadAll(null)).isEmpty();
    }

    @Test
    @DisplayName("缺少 id 直接报错，不能静默跳过")
    void missingIdIsRejected() {
        JSONObject json = JSONUtil.createObj()
                .set("prompt", "做个页面")
                .set("expectedType", "html");

        assertThatThrownBy(() -> EvalCase.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("expectedType 写成中文会报错，而不是当成未知类型放行")
    void unknownTypeIsRejected() {
        JSONObject json = JSONUtil.createObj()
                .set("id", "bad")
                .set("prompt", "做个页面")
                .set("expectedType", "HTML 单页");

        assertThatThrownBy(() -> EvalCase.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedType");
    }

    @Test
    @DisplayName("不写 timeoutSec 时用默认值，不写断言时为空列表")
    void defaultsAreApplied() {
        JSONObject json = JSONUtil.createObj()
                .set("id", "minimal")
                .set("prompt", "做个页面")
                .set("expectedType", "multi_file");

        EvalCase evalCase = EvalCase.parse(json);

        assertThat(evalCase.timeoutSec()).isEqualTo(180);
        assertThat(evalCase.mustInclude()).isEmpty();
        assertThat(evalCase.mustNotInclude()).isEmpty();
    }
}
