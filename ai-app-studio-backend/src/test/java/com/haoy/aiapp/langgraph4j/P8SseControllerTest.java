package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.config.AsyncRequestConfig;
import com.haoy.aiapp.controller.WorkflowSseController;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.service.app.AppService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.deleteDirs;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.facadeWritingCode;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.imageService;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.qualityAlwaysPass;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.routing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P8 测试 —— <b>SSE 流式输出</b>
 * <p>
 * 前七期做完，工作流已经能跑，但调用方要盯着服务端日志才知道走到哪一步了。这一期把它变成
 * 「边跑边推」：每完成一个节点就往前端推一个事件，前端据此显示实时进度。
 * <p>
 * 两个要钉死的东西：
 * <ol>
 *   <li><b>事件序列</b>：{@code workflow_start} → 若干 {@code step_completed} → {@code workflow_completed}，
 *       且结束事件里带上最终目录（否则前端拿不到产物在哪）。</li>
 *   <li><b>SSE 文本必须原样透传、不能被二次包裹</b>。这条是本期最容易踩的坑：
 *       工作流吐出的字符串已经是成型的 SSE 文本，若交给 Spring MVC 的 SSE 写入器，
 *       它会把整段当成一个 data 字段再包一层，事件名就没了（实测证据见
 *       {@link WorkflowSseController} 的类注释）。所以控制器用 {@code StreamingResponseBody} 直接写字节。</li>
 * </ol>
 * 全程假依赖，1 秒内跑完，不打 LLM、不跑 npm。
 *
 * @author <a>程序员haoy</a>
 */
class P8SseControllerTest {

    private static final String[] TEMP_DIRS = {"html_990301"};

    @AfterAll
    static void cleanTempDirs() {
        deleteDirs(TEMP_DIRS);
    }

    /**
     * 用真实工作流（依赖全是假的）来产出事件 —— 保证事件序列是「真跑出来的」而不是造出来的
     */
    private static CodeGenWorkflow stubWorkflow() {
        return new CodeGenWorkflow(imageService(), routing(CodeGenTypeEnum.HTML), facadeWritingCode(),
                qualityAlwaysPass(), mock(VueProjectBuilder.class));
    }

    /**
     * 造一个「appId 校验能过」的控制器
     * <p>
     * 控制器现在会校验 appId 指向的应用真实存在（为了关掉 {@code appId=0} 那个多人共写同一目录的后门），
     * 所以这里把 AppService 打桩成「查得到」。本类测的是 SSE 文本透传，不关心应用本身是什么。
     */
    private static WorkflowSseController controllerWithStubApp() {
        AppService appService = mock(AppService.class);
        when(appService.getById(anyLong())).thenReturn(new App());
        return new WorkflowSseController(stubWorkflow(), appService);
    }

    /**
     * 事件序列：开头是 start、结尾是 completed，中间每个执行的节点各推一次进度。
     * <p>
     * HTML 类型下实际执行 5 个节点（图片收集 / 提示词增强 / 智能路由 / 代码生成 / 代码质检），
     * 项目构建被条件边跳过 —— 所以这里断言 ≥5 而不是 =6。
     */
    @Test
    void fluxShouldEmitStartStepsAndCompletedInOrder() {
        List<String> events = stubWorkflow().executeWorkflowWithFlux("做一个技术博客", 990301L)
                .collectList().block();

        assertNotNull(events, "事件流不能为空");
        assertTrue(events.get(0).startsWith("event: workflow_start"), "首个事件必须是 workflow_start");
        assertTrue(events.get(events.size() - 1).startsWith("event: workflow_completed"),
                "最后一个事件必须是 workflow_completed");

        long stepCount = events.stream().filter(event -> event.startsWith("event: step_completed")).count();
        assertTrue(stepCount >= 5, "每个执行的节点都要推一次进度，实际=" + stepCount);

        String last = events.get(events.size() - 1);
        assertTrue(last.contains("buildResultDir"), "结束事件要带上最终目录，否则前端拿不到产物");
        // 每个事件都必须以空行结尾，否则浏览器不会把它当成一个完整事件
        events.forEach(event -> assertTrue(event.endsWith("\n\n"), "SSE 事件必须以空行结尾：" + event));
    }

    /**
     * 控制器必须把成型的 SSE 文本<b>原样</b>写出去。
     * <p>
     * 判据很直接：如果被 Spring 的 SSE 写入器二次包裹过，正文开头会变成 {@code data:event: ...}；
     * 原样透传则一定以 {@code event: } 开头。
     */
    @Test
    void controllerShouldPassPreformattedSseThroughVerbatim() throws Exception {
        WorkflowSseController controller = controllerWithStubApp();

        ResponseEntity<StreamingResponseBody> response = controller.executeWorkflowFlux("做一个技术博客", 990301L);

        assertNotNull(response.getBody(), "必须返回响应体");
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);
        String sse = outputStream.toString(StandardCharsets.UTF_8);

        assertTrue(sse.startsWith("event: workflow_start"),
                "必须原样透传；若变成 'data:event: ' 说明被 SSE 写入器二次包裹了");
        assertTrue(sse.contains("event: step_completed"));
        assertTrue(sse.contains("event: workflow_completed"));
        assertTrue(sse.endsWith("\n\n"), "最后一段也要以空行结尾");
    }

    /**
     * SSE 帧格式本身：必须是 {@code event: 名\ndata: json\n\n}。
     * <p>
     * 少那个空行是最常见的错误 —— 浏览器会一直等下一段、把两个事件粘成一个。
     */
    @Test
    void formatSseEventShouldFollowSseFraming() {
        assertEquals("event: ping\ndata: {\"a\":1}\n\n",
                CodeGenWorkflow.formatSseEvent("ping", Map.of("a", 1)));
    }

    /**
     * 空需求必须在入口就挡掉，不能让它跑进工作流白烧一轮 token。
     */
    @Test
    void controllerShouldRejectBlankPrompt() {
        WorkflowSseController controller = controllerWithStubApp();
        assertThrows(BusinessException.class, () -> controller.executeWorkflowFlux("   ", 1L));
    }

    /**
     * <b>异步超时必须被调大</b> —— 否则上面那些事件一个都到不了浏览器。
     * <p>
     * 这条测试守的是一个「单元测试根本测不出来」的坑：{@code StreamingResponseBody} 走
     * Servlet 异步请求模型，而容器的异步超时默认只有 <b>30 秒</b>；工作流一次要跑几十秒到几分钟，
     * 于是连接会在 30 秒左右被容器掐掉 —— 现象是「收到前几个事件就断流」，
     * 但后端日志里工作流一切正常，极难往配置上想（本项目 2026-10-10 真实踩过）。
     * <p>
     * 所以这里直接断言「最终生效的超时」而不是断言某个常量：
     * 把配置去掉、或者被人手改小到 5 分钟以内，这条都会红。
     */
    @Test
    void asyncTimeoutShouldBeLongEnoughForAWholeWorkflowRun() {
        InspectableAsyncSupportConfigurer configurer = new InspectableAsyncSupportConfigurer();
        new AsyncRequestConfig().configureAsyncSupport(configurer);

        Long timeout = configurer.exposeTimeout();
        assertNotNull(timeout, "必须显式设置异步超时，否则会退回容器默认的 30 秒，SSE 必断");
        assertTrue(timeout >= Duration.ofMinutes(5).toMillis(),
                "工作流一次要跑几十秒到几分钟，异步超时必须远大于容器默认的 30 秒，实际="
                        + Duration.ofMillis(timeout).toMinutes() + " 分钟");
    }

    /**
     * {@code AsyncSupportConfigurer} 的 {@code getTimeout()} 是 protected，
     * 用子类把它暴露出来，好在测试里读到「真正会被 Spring 用到的那个值」。
     */
    private static class InspectableAsyncSupportConfigurer extends AsyncSupportConfigurer {

        Long exposeTimeout() {
            return getTimeout();
        }
    }
}
