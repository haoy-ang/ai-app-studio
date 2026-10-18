package com.haoy.aiapp.controller;

import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.langgraph4j.CodeGenWorkflow;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作流调试接口的鉴权与参数防护测试
 * <p>
 * <b>这个测试的重点不是「有没有拦住」，而是「拦住之后回了什么」。</b>
 * 该接口的返回类型是 {@code text/event-stream}，而鉴权/限流切面在方法体之前抛异常 ——
 * 异常处理如果按普通 JSON 回写，调用方拿到的是一个「响应头说是事件流、内容是 JSON」的四不像，
 * 浏览器 EventSource 只会显示一个无意义的「连接错误」。
 * <p>
 * 所以每个拒绝用例都断言两件事：状态码 200（SSE 已经写出去了，不该是 4xx/5xx）+ 响应体里有业务错误码。
 * 状态码是 200 看着别扭，但那是 SSE 的固有特性：错误是通过事件体传递的，不是 HTTP 状态。
 * <p>
 * 依赖全部 mock 掉（UserService / AppService / CodeGenWorkflow），所以不碰数据库、不烧 AI 额度。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowSseControllerAuthTest {

    private static final String URL = "/workflow/execute-flux";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AppService appService;

    @MockitoBean(name = "codeGenWorkflow")
    private CodeGenWorkflow codeGenWorkflow;

    /**
     * 造一个登录用户
     */
    private User userWithRole(String role) {
        User user = new User();
        user.setId(1001L);
        user.setUserRole(role);
        return user;
    }

    /**
     * 断言响应体里带某个业务错误码，且确实是 SSE 文本
     * <p>
     * 不解析 JSON，只查 code 数字 —— 这条测试要锁的是「走没走 SSE 回写这条路」，
     * 不是 JSON 字段长什么样（那个变了不该让这条测试红）。
     */
    private void assertSseError(String body, int errorCode) {
        assertThat(body)
                .as("响应体不是 SSE 事件流: %s", body)
                .contains("event: business-error")
                .contains(String.valueOf(errorCode));
    }

    @Test
    @DisplayName("未登录：回 SSE 的 business-error 事件，而不是 JSON 或 500")
    void anonymousRequestIsRejectedAsSse() throws Exception {
        when(userService.getLoginUser(any(HttpServletRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        String body = mockMvc.perform(get(URL).param("prompt", "做一个博客").param("appId", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertSseError(body, ErrorCode.NOT_LOGIN_ERROR.getCode());
    }

    @Test
    @DisplayName("普通用户：无管理员权限，拒绝")
    void normalUserIsRejected() throws Exception {
        when(userService.getLoginUser(any(HttpServletRequest.class)))
                .thenReturn(userWithRole("user"));

        String body = mockMvc.perform(get(URL).param("prompt", "做一个博客").param("appId", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertSseError(body, ErrorCode.NO_AUTH_ERROR.getCode());
    }

    @Test
    @DisplayName("管理员传 appId=0：拒绝，后门已关闭")
    void adminCannotUseZeroAppId() throws Exception {
        when(userService.getLoginUser(any(HttpServletRequest.class)))
                .thenReturn(userWithRole("admin"));

        String body = mockMvc.perform(get(URL).param("prompt", "做一个博客").param("appId", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertSseError(body, ErrorCode.PARAMS_ERROR.getCode());
    }

    @Test
    @DisplayName("管理员传不存在的 appId：拒绝，避免往不存在的目录写产物")
    void adminCannotUseMissingAppId() throws Exception {
        when(userService.getLoginUser(any(HttpServletRequest.class)))
                .thenReturn(userWithRole("admin"));
        when(appService.getById(anyLong())).thenReturn(null);

        String body = mockMvc.perform(get(URL).param("prompt", "做一个博客").param("appId", "123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertSseError(body, ErrorCode.NOT_FOUND_ERROR.getCode());
    }

    @Test
    @DisplayName("管理员 + 真实 appId：放行，返回事件流")
    void adminWithRealAppIdIsAllowed() throws Exception {
        when(userService.getLoginUser(any(HttpServletRequest.class)))
                .thenReturn(userWithRole("admin"));
        when(appService.getById(anyLong())).thenReturn(new App());
        when(codeGenWorkflow.executeWorkflowWithFlux(anyString(), anyLong()))
                .thenReturn(Flux.just("event: workflow_start\ndata: {\"message\":\"开始\"}\n\n"));

        // StreamingResponseBody 走 Servlet 异步模型：perform 返回时流还没写完，
        // 必须等异步分派完成后再读响应体，否则拿到的是空串
        MvcResult pending = mockMvc.perform(get(URL).param("prompt", "做一个博客").param("appId", "123"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("event: workflow_start")
                .doesNotContain("business-error");
    }
}
