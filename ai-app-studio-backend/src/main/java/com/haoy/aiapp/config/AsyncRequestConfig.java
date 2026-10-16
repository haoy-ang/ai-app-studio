package com.haoy.aiapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 异步请求（含 SSE）超时配置
 * <p>
 * <b>为什么需要这个类？</b>
 * <p>
 * SSE 推送进度这条链路（{@code StreamingResponseBody}）走的是 Servlet 的
 * <b>异步请求</b>模型，而异步请求有一个「最长存活时间」——超过就会由容器抛
 * {@code AsyncRequestTimeoutException} 并<b>切断连接</b>。Servlet 容器的默认值是
 * <b>30 秒</b>，这跟「生成本身要花多久」完全没有关系。
 * <p>
 * 而本项目的代码生成工作流是「一次调用跑完整条 AI 流水线」：调 LLM 路由、增强提示词、
 * 生成代码、质检、Vue 项目还要跑 npm build —— <b>几十秒到几分钟是常态</b>。
 * 于是就会出现一个非常迷惑的现象（本项目 2026-10-10 实测）：
 * <pre>
 *   浏览器/curl 只收到前 3 个事件，流突然断了；
 *   但服务端日志显示工作流几秒后自己好好跑完了。
 * </pre>
 * 看起来像「后端崩了」，其实是<b>连接被容器按 30 秒上限掐掉了</b>，业务代码一行没错。
 * <p>
 * <b>判据</b>：SSE 收到的进度在某个大致固定的时刻（30 秒左右）断掉、而后端日志里
 * 工作流仍在继续 → 先怀疑异步超时，而不是去查业务代码。
 * <p>
 * <b>为什么给 30 分钟这么长？</b>因为超时在这里是「防止连接永久挂死」的兜底，不是用户体验开关：
 * <ul>
 *   <li>设短了 → 正常生成被误杀（就是上面那个 bug）；</li>
 *   <li>设长了 → 唯一代价是「一个已卡死的连接多占一会儿资源」，而真正的客户端断开
 *       会在下一次写响应时抛 {@code IOException} 被控制器捕获，<b>不需要</b>靠超时来清理。</li>
 * </ul>
 * 所以宁可给宽，尤其 {@code VUE_PROJECT} 类型要走工具调用逐个改文件 + npm build，
 * 耗时远高于 HTML 类型。
 * <p>
 * <b>等价写法</b>：在 {@code application.yml} 里写 {@code spring.mvc.async.request-timeout: 30m}
 * 效果相同。这里用 Java 配置，是为了让「为什么是 30 分钟」这件事能跟代码放在一起。
 *
 * @author <a>程序员haoy</a>
 */
@Configuration
public class AsyncRequestConfig implements WebMvcConfigurer {

    /**
     * SSE / 异步请求的最长存活时间。
     * <p>
     * 取 30 分钟：正常一次生成（含 Vue 的 npm build）在几分钟内，留足余量。
     */
    public static final Duration ASYNC_REQUEST_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 调大异步请求超时，避免长时间运行的 SSE 被容器提前切断
     *
     * @param configurer Spring MVC 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(ASYNC_REQUEST_TIMEOUT.toMillis());
    }
}
