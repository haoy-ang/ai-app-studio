package com.haoy.aiapp.controller;

import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.projectdownload.ProjectDownloadService;
import com.haoy.aiapp.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员设置精选的两条约束
 * <p>
 * 这里测的不是「接口通不通」，而是两个会让管理员困惑的行为：
 * <ol>
 *   <li>未部署的应用不能被设为精选 —— 精选位是给人点开体验的，
 *       而卡片上的「查看作品」是 {@code v-if="deployKey"}，没部署就点不开；</li>
 *   <li>改完必须清缓存 —— 精选列表挂了 5 分钟 TTL，不清的话点完刷新看不到效果。</li>
 * </ol>
 * 依赖全部 mock，不碰数据库、不烧 AI 额度。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppControllerFeaturedTest {

    private static final String URL = "/app/admin/update";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private ProjectDownloadService projectDownloadService;

    @BeforeEach
    void setUp() {
        // @AuthCheck(mustRole = "admin") 的切面读的就是这个用户
        when(userService.getLoginUser(any()))
                .thenReturn(User.builder().id(1L).userRole("admin").build());
    }

    @Test
    @DisplayName("未部署的应用不能设为精选，且不会落库")
    void undeployedAppCannotBeFeatured() throws Exception {
        when(appService.getById(10L)).thenReturn(App.builder().id(10L).userId(2L).build());

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10,\"priority\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("尚未部署")));

        verify(appService, never()).updateById(any());
    }

    @Test
    @DisplayName("已部署的应用可以设为精选")
    void deployedAppCanBeFeatured() throws Exception {
        when(appService.getById(10L)).thenReturn(App.builder().id(10L).userId(2L).deployKey("abc123").build());
        when(appService.updateById(any())).thenReturn(true);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10,\"priority\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("取消精选不受部署状态限制")
    void cancelFeaturedDoesNotRequireDeployment() throws Exception {
        when(appService.getById(10L)).thenReturn(App.builder().id(10L).userId(2L).build());
        when(appService.updateById(any())).thenReturn(true);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10,\"priority\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("改动精选后清掉精选列表缓存，管理员不用等 5 分钟")
    void featuredChangeEvictsListCache() throws Exception {
        Cache cache = cacheManager.getCache("good_app_page");
        assertThat(cache).isNotNull();
        cache.put("seed-key", "seed-value");
        assertThat(cache.get("seed-key")).isNotNull();

        when(appService.getById(10L)).thenReturn(App.builder().id(10L).userId(2L).deployKey("abc123").build());
        when(appService.updateById(any())).thenReturn(true);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10,\"priority\":99}"))
                .andExpect(status().isOk());

        assertThat(cache.get("seed-key")).isNull();
    }
}
