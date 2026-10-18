package com.haoy.aiapp.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.annotation.AuthCheck;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.langgraph4j.CodeGenWorkflow;
import com.haoy.aiapp.ratelimit.annotation.RateLimit;
import com.haoy.aiapp.ratelimit.enums.RateLimitType;
import com.haoy.aiapp.service.app.AppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 代码生成工作流 SSE 控制器 —— 把工作流的「逐步进度」实时推给前端
 * <p>
 * 接口：{@code GET /api/workflow/execute-flux?prompt=xxx&appId=123}
 * <p>
 * <b>⚠️ 这里为什么要用 {@link StreamingResponseBody} 原样写字节，而不是用
 * {@code Flux<String>} + {@code produces=text/event-stream} 直接返回？</b>
 * <p>
 * 因为工作流吐出来的每一段字符串<b>已经是成型的 SSE 文本</b>（{@code "event: xxx\ndata: {...}\n\n"}）。
 * 如果直接把它当 {@code Flux<String>} 的响应体返回，Spring MVC 的 SSE 写入器会把它当作一个
 * <b>普通数据元素</b>再包一层 {@code data:} —— 实测（本项目探针，2026-10-10）结果是这样：
 * <pre>
 *   我们的意图：              event: workflow_start
 *                            data: {"message":"..."}
 *
 *   Spring 实际写出：         data:event: workflow_start
 *                            data:data: {"message":"..."}
 *                            data:
 * </pre>
 * 事件名被塞进了 data 里，浏览器 {@code addEventListener("workflow_start", ...)} 永远收不到 ——
 * 而且不报错，只是「前端什么都收不到」。所以这里把 Flux 消费出来、把已经成型的文本<b>逐段原样写进
 * 响应流</b>，绕开任何二次封装。
 * <p>
 * <b>为什么要 flush？</b> SSE 的全部价值就在于「实时」。不 flush 的话字节会先攒在缓冲区里，
 * 用户看到的不是逐步进度，而是「卡半天然后一次全出来」。
 * <p>
 * <b>⚠️ 这是一个调试接口，一次调用会真的跑完整条 AI 生成流水线（花钱、几十秒）。</b>
 * 它曾经完全没有防护：任何人只要能访问到地址，就能匿名把账号余额刷光。现在有三道闸：
 * <ol>
 *   <li>{@link AuthCheck} —— 必须登录且是管理员。注意这个切面在<b>方法体之前</b>抛异常，
 *       所以拒绝走的是 {@code GlobalExceptionHandler}，那条路必须能回写成 SSE 事件，
 *       否则前端只会看到一个笼统的「连接错误」（判据见 {@code handleSseError}）；</li>
 *   <li>{@link RateLimit} —— 按用户每分钟 2 次。管理员也会手滑连点；</li>
 *   <li>{@code appId} 必填且必须真实存在 —— 关掉原来「传 0 就脱离业务流程、产物落到
 *       {@code {type}_0}」的后门。那个后门在单人本机开发时无害，一旦部署就是多人共写同一个目录、
 *       互相覆盖产物，而且覆盖了还看不出来。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class WorkflowSseController {

    /**
     * 用串行版工作流。并发版（{@code codeGenConcurrentWorkflow}）是另一个 Bean，
     * 想切换的话把 {@code @Qualifier} 的名字改掉即可 —— 两者的事件契约完全一样。
     */
    private final CodeGenWorkflow codeGenWorkflow;

    /**
     * 只用来校验 appId 指向的应用真实存在，避免往一个不存在的目录里写产物
     */
    private final AppService appService;

    public WorkflowSseController(@Qualifier("codeGenWorkflow") CodeGenWorkflow codeGenWorkflow,
                                 AppService appService) {
        this.codeGenWorkflow = codeGenWorkflow;
        this.appService = appService;
    }

    /**
     * 执行代码生成工作流并以 SSE 推送进度
     *
     * @param prompt 用户需求，如「做一个个人博客」
     * @param appId  应用 ID；必须是当前库里真实存在的应用（不再接受 0）
     * @return SSE 响应（事件：{@code workflow_start} / {@code step_completed} / {@code workflow_completed} / {@code workflow_error}）
     */
    @GetMapping(value = "/execute-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @RateLimit(limitType = RateLimitType.USER, rate = 2, rateInterval = 60,
            message = "工作流调试请求过于频繁，请稍后再试")
    public ResponseEntity<StreamingResponseBody> executeWorkflowFlux(
            @RequestParam("prompt") String prompt,
            @RequestParam("appId") Long appId) {
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "需求不能为空");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR,
                "appId 必须是真实的已有应用，不再支持传 0 脱离业务流程");
        ThrowUtils.throwIf(appService.getById(appId) == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        Flux<String> eventFlux = codeGenWorkflow.executeWorkflowWithFlux(prompt, appId);
        StreamingResponseBody responseBody = outputStream -> writeEvents(eventFlux, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                // 若前面挂了 Nginx，这个头能让它别做缓冲，否则事件会被攒着一起发
                .header("X-Accel-Buffering", "no")
                .body(responseBody);
    }

    /**
     * 把事件流逐段写进响应流
     * <p>
     * ⚠️ 客户端断开时这里会抛 {@code IOException}：<b>只记日志并停止转发</b>。
     * 注意这<b>不会</b>停止后端已经在跑的生成（工作流跑在自己的虚拟线程上），
     * 这是本项目的已知行为 —— 想真正刹车只能靠工作流内部的次数上限。
     *
     * @param eventFlux    工作流产出的 SSE 文本流
     * @param outputStream HTTP 响应输出流
     */
    private void writeEvents(Flux<String> eventFlux, OutputStream outputStream) {
        Iterator<String> iterator = eventFlux.toIterable().iterator();
        while (iterator.hasNext()) {
            String event = iterator.next();
            try {
                outputStream.write(event.getBytes(StandardCharsets.UTF_8));
                // 每段都 flush：不 flush 就成了「攒够了一次性给」，SSE 的实时性就没了
                outputStream.flush();
            } catch (IOException e) {
                log.warn("SSE 写出失败（客户端可能已断开），停止转发：{}", e.getMessage());
                return;
            }
        }
        log.info("工作流 SSE 事件已全部推送完成");
    }
}
