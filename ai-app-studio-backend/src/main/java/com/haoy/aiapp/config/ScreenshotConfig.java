package com.haoy.aiapp.config;

import com.haoy.aiapp.utils.WebScreenshotUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * 截图相关的定时任务配置
 * <p>
 * 截图成功后单次目录会被清理，但根目录本身、以及**截图失败时残留**的目录需要定时任务兜底，
 * 否则服务器磁盘会慢性增长。
 *
 * @author <a>程序员haoy</a>
 */
@Configuration
@EnableScheduling
@Slf4j
public class ScreenshotConfig {

    /**
     * 临时截图目录的存活时长，超过这个时间未修改的目录会被清理
     */
    private static final Duration TEMP_FILE_TTL = Duration.ofHours(1);

    /**
     * 每天凌晨 2 点清理过期的临时截图目录
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupTempScreenshots() {
        log.info("开始定时清理过期的临时截图目录: {}", WebScreenshotUtils.getTempScreenshotDir());
        try {
            int deleted = WebScreenshotUtils.cleanupTempFiles(TEMP_FILE_TTL);
            log.info("定时清理临时截图目录完成，共清理 {} 个", deleted);
        } catch (Exception e) {
            // 定时任务里抛异常只会被调度器打日志，这里显式兜底便于排查
            log.error("定时清理临时截图目录失败", e);
        }
    }
}
