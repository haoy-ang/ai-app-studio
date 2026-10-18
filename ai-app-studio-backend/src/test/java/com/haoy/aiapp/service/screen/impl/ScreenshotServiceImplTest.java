package com.haoy.aiapp.service.screen.impl;

import com.haoy.aiapp.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 截图前的部署地址探测
 * <p>
 * 这几条测试的价值在于「把运维问题翻译成人话」：
 * Nginx 没启动 和 Nginx root 配错，在原来的实现里都会表现为
 * 「本地截图生成失败」甚至**静默成功**（把 404 页面当封面截下来）。
 * <p>
 * 用 JDK 自带的 {@link HttpServer} 起假站点，不依赖 nginx、不联网。
 *
 * @author <a>程序员haoy</a>
 */
class ScreenshotServiceImplTest {

    @Test
    @DisplayName("站点正常返回 200：探测放行")
    void reachableUrlPasses() throws IOException {
        withServer(200, server -> {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/abc123/";
            assertThatCode(() -> ScreenshotServiceImpl.assertReachable(url)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("站点返回 404：报错要点名 root 配置，不能截一张 404 页当封面")
    void notFoundUrlIsRejectedWithConfigHint() throws IOException {
        withServer(404, server -> {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/abc123/";
            assertThatThrownBy(() -> ScreenshotServiceImpl.assertReachable(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("HTTP 404")
                    .hasMessageContaining("root");
        });
    }

    @Test
    @DisplayName("端口没人监听：报错要点名 Nginx 没启动")
    void refusedConnectionIsRejectedWithNginxHint() throws IOException {
        int freePort = findFreePort();
        String url = "http://127.0.0.1:" + freePort + "/abc123/";

        assertThatThrownBy(() -> ScreenshotServiceImpl.assertReachable(url))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法连接")
                .hasMessageContaining("Nginx");
    }

    /**
     * 起一个只返回固定状态码的假站点，跑完自动关
     */
    private void withServer(int status, ServerConsumer consumer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "stub".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            consumer.accept(server);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 拿一个「当前没人监听」的端口：先占住再放掉
     */
    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface ServerConsumer {
        void accept(HttpServer server) throws IOException;
    }
}
