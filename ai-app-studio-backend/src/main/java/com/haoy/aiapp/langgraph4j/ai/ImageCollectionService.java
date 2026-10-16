package com.haoy.aiapp.langgraph4j.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集 AI 服务接口 —— AI 自主决定调哪几个图片工具、搜什么关键词
 * <p>
 * <b>为什么返回 {@code String} 而不是 {@code List<ImageResource>}？</b>
 * <br>
 * 最初的方案是想做结构化输出的（返回 {@code List<ImageResource>}），但实测走不通，原因有二：
 * <ol>
 *   <li>要返回 {@code List<POJO>}，langchain4j 必须开启
 *       {@code supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)} —— 而 <b>DeepSeek 不支持这个参数</b>；</li>
 *   <li>退而求其次用阿里云百炼（兼容 OpenAI）时，AI 的<b>工具调用意图被错误地当成结构化输出返回值去解析</b>，
 *       同样失败。</li>
 * </ol>
 * 最终的解法是：用 DeepSeek、<b>不开结构化输出</b>、方法直接返回 {@code String}，
 * 由后续的「提示词增强节点」把这段文本原样拼进提示词里交给模型。
 * 代价是不太好编程式校验，收益是能稳定跑通 —— 这一节的图片收集是「增强项」，稳定比精确更重要。
 * <p>
 * ⚠️ 提示词 {@code image-collection-system-prompt.txt} 里必须出现 "json" 字样：
 * 共享的 {@code chatModel} 全局开了 {@code response-format: json_object}，
 * 而 DeepSeek 要求该模式下提示词必须含 "json"，否则直接 400。
 *
 * @author <a>程序员haoy</a>
 */
public interface ImageCollectionService {

    /**
     * 根据用户提示词收集所需的图片资源
     * <p>
     * AI 会根据需求自主选择调用相应的工具（内容图 / 插画 / 架构图 / Logo）。
     *
     * @param userPrompt 用户原始需求
     * @return AI 汇总出来的图片信息文本（JSON 字符串）
     */
    @SystemMessage(fromResource = "prompt/image-collection-system-prompt.txt")
    String collectImages(@UserMessage String userPrompt);
}
