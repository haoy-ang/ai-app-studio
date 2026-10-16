package com.haoy.aiapp.langgraph4j.model;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片类别枚举 —— 工作流收集到的图片按用途分类
 * <p>
 * 分类的意义在于「提示词增强」环节：给 AI 的素材清单里写清每张图是干什么用的，
 * 它才知道该把架构图放在技术介绍区、把 Logo 放在页头，而不是随便塞。
 * <p>
 * 四个类别分别对应四种收集渠道，与 {@code langgraph4j.tools} 下的四个工具一一对应：
 * <ul>
 *   <li>{@link #CONTENT} —— Pexels 关键词搜索</li>
 *   <li>{@link #ILLUSTRATION} —— unDraw 插画库</li>
 *   <li>{@link #ARCHITECTURE} —— Mermaid 代码渲染成图</li>
 *   <li>{@link #LOGO} —— AI 文生图</li>
 * </ul>
 *
 * @author <a>程序员haoy</a>
 */
@Getter
public enum ImageCategoryEnum {

    CONTENT("内容图片", "CONTENT"),
    LOGO("LOGO图片", "LOGO"),
    ILLUSTRATION("插画图片", "ILLUSTRATION"),
    ARCHITECTURE("架构图片", "ARCHITECTURE");

    /**
     * 中文描述（用于拼进提示词给 AI 看）
     */
    private final String text;

    /**
     * 英文标识（用于序列化 / 与前端或 AI 的 JSON 对接）
     */
    private final String value;

    ImageCategoryEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值，找不到返回 null
     */
    public static ImageCategoryEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ImageCategoryEnum anEnum : ImageCategoryEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
