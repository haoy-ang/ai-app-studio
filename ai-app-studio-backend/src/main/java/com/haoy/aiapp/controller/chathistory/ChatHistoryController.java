package com.haoy.aiapp.controller.chathistory;

import com.haoy.aiapp.annotation.AuthCheck;
import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.DeleteRequest;
import com.haoy.aiapp.common.ResultUtils;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.model.dto.chathistory.ChatHistoryQueryRequest;
import com.haoy.aiapp.model.entity.ChatHistory;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import com.haoy.aiapp.service.user.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 对话历史 控制层。
 *
 * @author <a>程序员haoy</a>
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private UserService userService;

    /**
     * 【用户】游标分页查询某个应用的对话历史（仅应用创建者和管理员可见）
     * <p>
     * 不传游标时返回最新的一页；把上一页最后一条记录的 <b>id</b> 作为 lastId 再次请求，
     * 就能继续向前加载更早的历史消息。
     * <p>
     * ⭐ 游标优先取 lastId：id 是雪花号，单调递增且唯一；
     * createTime 精度只到秒，用户消息和 AI 消息常在同一秒，用时间做游标会漏记录。
     * lastCreateTime 仅为兼容老客户端保留。
     *
     * @param appId          应用 id
     * @param pageSize       每页条数，默认 10
     * @param lastId         游标：上一页最后一条记录的 id（推荐）
     * @param lastCreateTime 旧游标：上一页最后一条记录的创建时间（兼容用，lastId 非空时忽略）
     * @param request        请求对象
     * @return 对话历史分页（按创建时间降序）
     */
    @GetMapping("/app/{appId}")
    public BaseResponse<Page<ChatHistory>> listAppChatHistory(@PathVariable Long appId,
                                                              @RequestParam(defaultValue = "10") int pageSize,
                                                              @RequestParam(required = false) Long lastId,
                                                              @RequestParam(required = false)
                                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                              LocalDateTime lastCreateTime,
                                                              HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Page<ChatHistory> chatHistoryPage = chatHistoryService.listAppChatHistoryByPage(
                appId, pageSize, lastId, lastCreateTime, loginUser);
        return ResultUtils.success(chatHistoryPage);
    }

    /**
     * 【管理员】分页查询所有应用的对话历史（按创建时间降序，便于内容监管）
     *
     * @param chatHistoryQueryRequest 查询请求
     * @return 对话历史分页
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistory>> listAllChatHistoryByPageForAdmin(
            @RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        // 查询数据（管理员不做应用归属校验，且不传游标，走普通分页）
        QueryWrapper queryWrapper = chatHistoryService.getQueryWrapper(chatHistoryQueryRequest);
        Page<ChatHistory> chatHistoryPage = chatHistoryService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(chatHistoryPage);
    }

    /**
     * 【管理员】删除单条对话历史（违规内容清理）
     * <p>
     * 说明：前端对话管理页有删除按钮，但并没有配套的删除接口，所以这里补一个。
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteChatHistoryByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        boolean result = chatHistoryService.removeById(deleteRequest.getId());
        return ResultUtils.success(result);
    }
}
