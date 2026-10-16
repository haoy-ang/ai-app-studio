package com.haoy.aiapp.langgraph4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图片资源对象 —— 工作流在各节点之间传递的「一张图」
 * <p>
 * 之所以不是简单的一个 URL 字符串，是因为「提示词增强」节点需要把
 * 类别 + 描述 + 地址 三样一起告诉 AI，它才能把图放到合适的位置。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 图片类别
     */
    private ImageCategoryEnum category;

    /**
     * 图片描述 —— 会写进提示词，所以尽量是「这张图是干什么用的」而不是文件名
     */
    private String description;

    /**
     * 图片地址（外部直链或上传到 COS 后的地址）
     */
    private String url;
}
