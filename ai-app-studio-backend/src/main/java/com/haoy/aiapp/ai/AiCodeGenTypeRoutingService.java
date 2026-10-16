package com.haoy.aiapp.ai;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;

/**
 * AI 代码生成类型智能路由服务
 * <p>
 * 使用结构化输出直接返回枚举类型:方法返回值是 {@link CodeGenTypeEnum},
 * 框架会自动把返回类型转成 JSON Schema 写进提示词,再把模型输出反序列化成枚举,
 * 所以我们不需要自己拼 JSON、也不需要手动解析。
 *
 * @author <a>程序员haoy</a>
 */
public interface AiCodeGenTypeRoutingService {

    /**
     * 根据用户需求智能选择代码生成类型
     *
     * @param userPrompt 用户输入的需求描述
     * @return 推荐的代码生成类型
     */
    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(String userPrompt);
}
