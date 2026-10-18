package com.haoy.aiapp.eval;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一条评测用例
 * <p>
 * <b>一条用例 = 一段需求 + 一份「合格」的客观定义。</b>定义必须能在不看代码的情况下机械判断，
 * 否则不同人、不同时间跑出来的结论没法比较。
 * <p>
 * 三类断言各管一件事：
 * <ul>
 *   <li>{@code expectedType} —— 度量<b>路由准确率</b>。它是 AI 的独立决策，错了后面全白搭；</li>
 *   <li>{@code mustInclude} —— 需求里明确要求的东西（「要有导航栏」「要有页脚」）；</li>
 *   <li>{@code mustNotInclude} —— 明确不该出现的（占位符、某些禁用词）。</li>
 * </ul>
 * <p>
 * 刻意<b>不支持</b>「AI 打个分」这种字段：评分尺子如果是模型，那测的就是模型的自我一致性，
 * 不是产物质量。要更细的判断就加确定性断言，加不出来就说明这条用例还没想清楚。
 *
 * @param id             用例标识，报告 diff 的主键 —— 改名等于让历史基线失效
 * @param prompt         喂给 AI 的需求原文
 * @param expectedType   期望的生成类型，用来算路由准确率
 * @param mustInclude    产物里应该出现的片段（在所有文本文件中搜）
 * @param mustNotInclude 产物里不该出现的片段
 * @param timeoutSec     单条超时（秒）
 * @param note           人话备注：为什么要这条用例、它红了代表什么
 * @author <a>程序员haoy</a>
 */
public record EvalCase(
        String id,
        String prompt,
        CodeGenTypeEnum expectedType,
        List<String> mustInclude,
        List<String> mustNotInclude,
        int timeoutSec,
        String note
) {

    /**
     * 默认超时：一次完整生成（含可能的返工）留 3 分钟余量
     */
    private static final int DEFAULT_TIMEOUT_SEC = 180;

    public EvalCase {
        mustInclude = mustInclude == null ? List.of() : List.copyOf(mustInclude);
        mustNotInclude = mustNotInclude == null ? List.of() : List.copyOf(mustNotInclude);
        timeoutSec = timeoutSec <= 0 ? DEFAULT_TIMEOUT_SEC : timeoutSec;
    }

    /**
     * 从目录加载全部用例（{@code *.json}，按文件名排序保证顺序稳定）
     *
     * @param dir 用例目录，如 {@code .workbuddy/eval/cases}
     * @return 用例列表；目录不存在时返回空列表
     */
    public static List<EvalCase> loadAll(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            List<EvalCase> cases = new ArrayList<>();
            for (Path file : files) {
                cases.add(parse(JSONUtil.parseObj(Files.readString(file, StandardCharsets.UTF_8))));
            }
            return List.copyOf(cases);
        } catch (IOException e) {
            throw new IllegalStateException("读取评测用例失败: " + dir, e);
        }
    }

    /**
     * 解析单条用例
     *
     * @param json 用例 JSON
     * @return 用例
     */
    public static EvalCase parse(JSONObject json) {
        String id = json.getStr("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例缺少 id: " + json);
        }
        String typeValue = json.getStr("expectedType");
        CodeGenTypeEnum expectedType = CodeGenTypeEnum.getEnumByValue(typeValue);
        if (expectedType == null) {
            throw new IllegalArgumentException("用例 " + id + " 的 expectedType 非法: " + typeValue);
        }
        return new EvalCase(
                id,
                json.getStr("prompt"),
                expectedType,
                strList(json.getJSONArray("mustInclude")),
                strList(json.getJSONArray("mustNotInclude")),
                json.getInt("timeoutSec", DEFAULT_TIMEOUT_SEC),
                json.getStr("note"));
    }

    private static List<String> strList(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        return array.stream().map(String::valueOf).toList();
    }
}
