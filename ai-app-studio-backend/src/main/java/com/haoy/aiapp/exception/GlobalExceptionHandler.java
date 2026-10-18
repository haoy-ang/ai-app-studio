package com.haoy.aiapp.exception;

import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.ResultUtils;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.guardrail.InputGuardrailException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理器
 * <p>
 * 注意：这里必须加 @Hidden，原因见下。
 * <p>
 * Knife4j 4.4.0 传递依赖的 springdoc-openapi 是 2.3.0，而 2.3.0 是给 Spring Boot 3.2 / Spring 6.0 用的。
 * 本项目是 Spring Boot 3.5.3（Spring 6.2.8），Spring 6.2 已经把
 * {@code ControllerAdviceBean(Object)} 这个单参构造函数删掉了，只剩三参版本。
 * <p>
 * 当 springdoc 扫描 @ControllerAdvice 类来生成"通用错误响应"文档时，
 * GenericResponseService#getGenericMapResponse 里会执行
 * {@code new ControllerAdviceBean(controllerAdviceInfo.getControllerAdvice())}，
 * 运行时找不到该构造函数，直接抛 NoSuchMethodError（属于 Error，@ExceptionHandler 接不住），
 * 表现就是 /api/v3/api-docs 报 500、doc.html 白屏。
 * <p>
 * 加 @Hidden 后，springdoc 的 OpenAPIService#getControllerAdviceMap 会把本类过滤掉，
 * 上面那行代码就不会执行，从而绕开这个版本冲突。
 * <p>
 * 为什么不直接把 springdoc 升到 2.8.x？因为 Knife4j 官方适配 Spring Boot 3.5.x 的版本（4.6.0）
 * 并未发布到 Maven 中央仓库，硬升 springdoc 会让 Knife4j 内部类因 API 变更而报错，代价更大。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@RestControllerAdvice
@Hidden
public class GlobalExceptionHandler {

    /**
     * 需要按 SSE 格式回写错误的接口路径
     * <p>
     * <b>为什么要把路径列出来、而不是只靠 {@code Accept} 头判断？</b>
     * 因为 {@code Accept} 是<b>客户端</b>给的，curl / Postman / 部分 fetch 调用默认都不带
     * {@code text/event-stream}。而这些接口上的 {@code @AuthCheck} / {@code @RateLimit} 切面
     * 是在方法体<b>之前</b>抛异常的 —— 此时响应头已经被 {@code produces} 定成事件流，
     * 如果按 JSON 回写，调用方会拿到一个「声称是事件流、实际是 JSON」的响应，谁都解析不了。
     * <p>
     * ⚠️ 新增 SSE 接口时必须往这里加一条，否则该接口的鉴权/限流失败会退化成上面那种四不像响应。
     */
    private static final List<String> SSE_URI_PATTERNS = List.of(
            "/chat/gen/code",
            "/workflow/execute-flux");

    /**
     * 处理业务异常
     * <p>
     * 业务异常是「预期内」的结果（未登录、参数错误、无权限等），调用方本来就会拿到对应错误码，
     * 因此这里只记一行 warn、不打堆栈：
     * 一是堆栈对定位这类问题没有帮助（异常点就是业务判断本身）；
     * 二是「未登录」这类高频异常会把日志刷满，让真正的系统错误被淹没。
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getCode(), e.getMessage());
        // SSE 请求（如限流触发）要按事件流回错误，否则前端 EventSource 只会看到一个笼统的「连接错误」
        if (handleSseError(e.getCode(), e.getMessage())) {
            return null;
        }
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理运行时异常
     * <p>
     * 同样先试 SSE 回写：SSE 接口在流建立前的失败（如限流切面抛的异常、参数校验失败）
     * 都会走到这里，走 JSON 分支前端同样看不懂。
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        if (handleSseError(ErrorCode.SYSTEM_ERROR.getCode(), "系统错误")) {
            return null;
        }
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * 处理输入护轨异常
     * <p>
     * 流式路径下拦截信息已经在 {@code AiCodeGeneratorFacade} 里就地转成消息了，
     * 这里是「非流式 / 未走到那条路」时的兜底，保证任何入口触发护轨都不会吐 500。
     * 错误码用 PARAMS_ERROR —— 本质就是用户参数不合法。
     */
    @ExceptionHandler(InputGuardrailException.class)
    public BaseResponse<?> inputGuardrailExceptionHandler(InputGuardrailException e) {
        log.warn("InputGuardrailException: {}", e.getMessage());
        if (handleSseError(ErrorCode.PARAMS_ERROR.getCode(), e.getMessage())) {
            return null;
        }
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
    }

    /**
     * 尝试按 SSE 格式回写错误
     * <p>
     * 背景：限流异常是在进入 SSE 接口<b>之前</b>由切面抛出的，此时 Spring 还没开始写
     * {@code text/event-stream}，如果按普通 JSON 返回，前端的 EventSource 拿不到 body、
     * 只会报一个「连接错误」，用户完全不知道是被限流了。
     * <p>
     * 判据：Accept 头含 {@code text/event-stream}，或 URI 命中 SSE 接口路径。
     * 二者取或 —— 有些客户端（含浏览器 fetch 流）不带 Accept，此时靠路径兜底。
     *
     * @param errorCode    错误码
     * @param errorMessage 错误信息
     * @return true 表示「是 SSE 请求且已按事件流处理」，调用方应直接 return null
     */
    private boolean handleSseError(int errorCode, String errorMessage) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        if (response == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        boolean isSse = (accept != null && accept.contains("text/event-stream"))
                || (uri != null && SSE_URI_PATTERNS.stream().anyMatch(uri::contains));
        if (!isSse) {
            return false;
        }
        try {
            // 设置 SSE 响应头
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            // 构造错误消息的 SSE 格式
            Map<String, Object> errorData = Map.of(
                    "error", true,
                    "code", errorCode,
                    "message", errorMessage);
            String errorJson = JSONUtil.toJsonStr(errorData);
            // 用自定义事件名 business-error，避开浏览器内建的 error 事件
            response.getWriter().write("event: business-error\ndata: " + errorJson + "\n\n");
            response.getWriter().flush();
            // 结束事件，告知前端流已收尾
            response.getWriter().write("event: done\ndata: {}\n\n");
            response.getWriter().flush();
        } catch (IOException ioException) {
            // 响应可能已经 committed（写过头了），此时再写会抛 IllegalStateException/IOException。
            // 必须兜住，否则会把「原始的业务异常」盖成一个看不懂的 IO 异常。
            log.error("回写 SSE 错误响应失败", ioException);
        }
        // 只要判定是 SSE 请求就返回 true：JSON 响应体对 EventSource 是无意义的
        return true;
    }
}