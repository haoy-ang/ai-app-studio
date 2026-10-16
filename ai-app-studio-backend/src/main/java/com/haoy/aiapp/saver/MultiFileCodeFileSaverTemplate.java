package com.haoy.aiapp.saver;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

/**
 * 多文件代码保存器
 */
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        // 至少要有 HTML 代码，CSS 和 JS 可以为空
        String htmlCode = result.getHtmlCode();
        if (StrUtil.isBlank(htmlCode)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码内容不能为空");
        }
        // ⚠️ 提示词要求 index.html 通过 <link>/<script> 外链 style.css 和 script.js，
        // 但模型偶尔只吐一个 html 代码块（实测两次连续复现）。此时 writeToFile 会「静默跳过」空内容，
        // 结果是：页面上线了，却没有样式、没有交互，而且 index.html 里还挂着两个 404 的外链。
        // 与其静默产出一个坏项目，不如直接抛错让用户重试。
        if (StrUtil.isBlank(result.getCssCode()) && htmlCode.contains("style.css")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "多文件生成不完整：index.html 引用了 style.css，但模型没有返回 CSS 代码块，请重试");
        }
        if (StrUtil.isBlank(result.getJsCode()) && htmlCode.contains("script.js")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "多文件生成不完整：index.html 引用了 script.js，但模型没有返回 JS 代码块，请重试");
        }
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
        writeToFile(baseDirPath, "style.css", result.getCssCode());
        writeToFile(baseDirPath, "script.js", result.getJsCode());
    }
}