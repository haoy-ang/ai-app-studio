package com.haoy.aiapp.service.appversion.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.config.AppVersionProperties;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.mapper.app.AppMapper;
import com.haoy.aiapp.mapper.appversion.AppVersionMapper;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.AppVersion;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.model.enums.VersionSourceEnum;
import com.haoy.aiapp.service.appversion.AppVersionService;
import com.haoy.aiapp.version.AppVersionStorage;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用版本服务实现
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Service
public class AppVersionServiceImpl extends ServiceImpl<AppVersionMapper, AppVersion> implements AppVersionService {

    /**
     * 版本号计数器在 Redis 里的 key 前缀
     * <p>
     * 为什么不用「表里最大的 versionNo + 1」：版本可以被用户删掉，
     * 删掉最大号之后再生成就会复用一个已经存在过的号，
     * 而 {@code UK(appId, versionNo)} 不认逻辑删除，直接撞主键。
     * Redis 自增单调不回退，删多少条都不会复用号。
     */
    private static final String SEQ_KEY_PREFIX = "app:version:seq:";

    /**
     * 每个应用一把锁：同一个应用的「打快照 / 回滚」必须串行
     * <p>
     * 这两件事都会改动同一个产物目录，并发执行会写出一份「一半旧一半新」的产物。
     * 单实例部署下这把锁足够；多实例时还有 UK(appId, versionNo) 兜底
     * （撞号那次会放弃记版本，不会写坏数据）。
     */
    private static final ConcurrentMap<Long, ReentrantLock> APP_LOCKS = new ConcurrentHashMap<>();

    /**
     * 版本表里 prompt 字段的最大长度（varchar(512) 留点余量）
     */
    private static final int PROMPT_MAX_LENGTH = 500;

    @Resource
    private AppVersionProperties properties;

    @Resource
    private AppVersionStorage storage;

    /**
     * 这里刻意直接依赖 {@link AppMapper} 而不是 {@code AppService}：
     * {@code AppServiceImpl#removeById} 需要调本服务清理版本，若这里反过来注入 AppService
     * 就构成循环依赖 —— Spring Boot 2.6 起默认禁止循环依赖，应用会直接起不来。
     */
    @Resource
    private AppMapper appMapper;

    @Resource
    private RedissonClient redissonClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<AppVersion> snapshot(long appId, CodeGenTypeEnum codeGenType,
                                         VersionSourceEnum source, String prompt, Long userId) {
        if (!properties.isEnabled() || appId <= 0 || codeGenType == null) {
            return Optional.empty();
        }
        // 先确认真有这么一个应用：评估回归（appId 从 90 万起）和门面单测（appId=1）
        // 用的都是「只在磁盘上存在、库里没有」的伪 id，不拦的话版本表里会堆一堆
        // 指向不存在应用的幽灵记录，连回滚都点不了。
        // 一次主键查询换数据干净，值得。
        App app = appMapper.selectOneById(appId);
        if (app == null) {
            log.debug("快照跳过：应用不存在，appId: {}", appId);
            return Optional.empty();
        }
        Path sourceDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenType.getValue() + "_" + appId);
        int versionNo = -1;
        ReentrantLock lock = lockOf(appId);
        lock.lock();
        try {
            // 先「描述」再「拷贝」：内容与上一版一致时，一个字节都不用往磁盘写
            AppVersionStorage.Manifest manifest = storage.describe(sourceDir);
            if (manifest == null) {
                return Optional.empty();
            }
            AppVersion latest = getLatest(appId);
            if (latest != null && manifest.contentHash().equals(latest.getContentHash())) {
                // 内容没变（模型照原样又生成了一遍，或者回滚后重新生成回了旧内容）：
                // 不记新版本，但要把「当前版本」指到它身上，否则 current 会指向一个内容已不存在的版本
                markCurrent(appId, latest.getId());
                log.info("产物内容与最新版本一致，跳过记录，appId: {}，versionNo: {}", appId, latest.getVersionNo());
                return Optional.of(latest);
            }
            versionNo = nextVersionNo(appId);
            if (!storage.copy(sourceDir, appId, versionNo)) {
                return Optional.empty();
            }
            AppVersion version = AppVersion.builder()
                    .appId(appId)
                    .versionNo(versionNo)
                    .codeGenType(codeGenType.getValue())
                    .contentHash(manifest.contentHash())
                    .fileCount(manifest.fileCount())
                    .sizeBytes(manifest.sizeBytes())
                    .storagePath(storage.storagePath(appId, versionNo))
                    .source(source == null ? VersionSourceEnum.CHAT.getValue() : source.getValue())
                    .prompt(StrUtil.sub(prompt, 0, PROMPT_MAX_LENGTH))
                    // 门面不知道操作人是谁（它只拿到 appId），所以这里用应用归属人兜底
                    .userId(userId == null ? app.getUserId() : userId)
                    .isCurrent(1)
                    .build();
            markCurrent(appId, null);
            boolean saved = this.save(version);
            if (!saved) {
                storage.delete(appId, versionNo);
                return Optional.empty();
            }
            prune(appId);
            log.info("已生成版本快照，appId: {}，versionNo: {}，文件数: {}，体积: {} 字节",
                    appId, versionNo, manifest.fileCount(), manifest.sizeBytes());
            return Optional.of(version);
        } catch (DuplicateKeyException e) {
            // 只可能在多实例并发时发生：号码被别的实例抢先用了。放弃这次记录，绝不写坏数据
            log.warn("版本号冲突，放弃本次快照，appId: {}，versionNo: {}", appId, versionNo);
            resetCounter(appId);
            storage.delete(appId, versionNo);
            return Optional.empty();
        } catch (Exception e) {
            // 版本记录只是锦上添花：任何异常都不能冒到「生成」这条主链路上
            log.error("生成版本快照失败，appId: {}，versionNo: {}", appId, versionNo, e);
            if (versionNo > 0) {
                storage.delete(appId, versionNo);
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<AppVersion> listByAppId(long appId) {
        if (appId <= 0) {
            return List.of();
        }
        return this.list(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("versionNo", false));
    }

    @Override
    public AppVersion getCurrent(long appId) {
        if (appId <= 0) {
            return null;
        }
        List<AppVersion> versions = this.list(QueryWrapper.create()
                .eq("appId", appId)
                .eq("isCurrent", 1)
                .orderBy("versionNo", false)
                .limit(1));
        return CollUtil.isEmpty(versions) ? null : versions.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppVersion rollback(long versionId, User loginUser) {
        ThrowUtils.throwIf(versionId <= 0, ErrorCode.PARAMS_ERROR, "版本 id 无效");
        AppVersion version = this.getById(versionId);
        ThrowUtils.throwIf(version == null, ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        App app = appMapper.selectOneById(version.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        checkOwner(app, loginUser);
        // 类型必须一致：目录名是由类型拼出来的，不一致说明数据已经脏了，
        // 这时候「回滚」会往另一个目录写文件，看起来成功、实际什么都没变
        ThrowUtils.throwIf(!StrUtil.equals(version.getCodeGenType(), app.getCodeGenType()),
                ErrorCode.SYSTEM_ERROR, "版本类型与应用当前类型不一致，无法回滚");
        long appId = app.getId();
        ReentrantLock lock = lockOf(appId);
        lock.lock();
        try {
            Path targetDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    app.getCodeGenType() + "_" + appId);
            // ⚠️ 顺序很重要：先写盘、后改库。
            // 反过来的话，一旦写盘失败，库里已经标成「回滚成功」，用户看到的是「回滚了但页面没变」；
            // 而现在这个顺序最坏情况是「文件已回滚、库还没切标记」，重试一次就能修好。
            boolean restored = storage.restore(appId, version.getVersionNo(), targetDir);
            ThrowUtils.throwIf(!restored, ErrorCode.SYSTEM_ERROR, "回滚失败：快照文件写入出错");
            markCurrent(appId, versionId);
            log.info("应用回滚完成，appId: {}，回滚到 versionNo: {}", appId, version.getVersionNo());
            return version;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteVersion(long versionId, User loginUser) {
        ThrowUtils.throwIf(versionId <= 0, ErrorCode.PARAMS_ERROR, "版本 id 无效");
        AppVersion version = this.getById(versionId);
        ThrowUtils.throwIf(version == null, ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        App app = appMapper.selectOneById(version.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        checkOwner(app, loginUser);
        ThrowUtils.throwIf(Integer.valueOf(1).equals(version.getIsCurrent()),
                ErrorCode.OPERATION_ERROR, "当前版本不能删除，请先回滚到其它版本");
        // 先删磁盘再删库：反过来的话，一旦删盘失败就变成「库里没有、磁盘上还占着」的孤儿目录
        storage.delete(version.getAppId(), version.getVersionNo());
        return this.removeById(versionId);
    }

    @Override
    public void deleteByAppId(long appId) {
        if (appId <= 0) {
            return;
        }
        try {
            List<AppVersion> versions = listByAppId(appId);
            for (AppVersion version : versions) {
                this.removeById(version.getId());
            }
            storage.deleteAll(appId);
        } catch (Exception e) {
            // 删应用是主流程，版本清理失败只记日志
            log.error("清理应用版本失败，appId: {}", appId, e);
        }
    }

    /**
     * 取最新版本（按 versionNo 倒序取第一条）
     */
    private AppVersion getLatest(long appId) {
        List<AppVersion> versions = this.list(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("versionNo", false)
                .limit(1));
        return CollUtil.isEmpty(versions) ? null : versions.get(0);
    }

    /**
     * 切换「当前版本」标记
     *
     * @param appId        应用 id
     * @param currentId    要标记为当前的版本 id；传 null 表示只清空、不设置（用于先清后插）
     */
    private void markCurrent(long appId, Long currentId) {
        AppVersion clear = AppVersion.builder().isCurrent(0).build();
        this.update(clear, QueryWrapper.create()
                .eq("appId", appId)
                .eq("isCurrent", 1));
        if (currentId != null) {
            AppVersion mark = AppVersion.builder().id(currentId).isCurrent(1).build();
            this.updateById(mark);
        }
    }

    /**
     * 生成下一个版本号（Redis 自增；key 不存在时用库里的最大号初始化）
     */
    private int nextVersionNo(long appId) {
        RAtomicLong counter = redissonClient.getAtomicLong(SEQ_KEY_PREFIX + appId);
        if (!counter.isExists()) {
            AppVersion latest = getLatest(appId);
            counter.set(latest == null ? 0 : latest.getVersionNo());
        }
        return (int) counter.incrementAndGet();
    }

    /**
     * 计数器与库里最大号对齐（撞号后自愈）
     */
    private void resetCounter(long appId) {
        try {
            AppVersion latest = getLatest(appId);
            redissonClient.getAtomicLong(SEQ_KEY_PREFIX + appId)
                    .set(latest == null ? 0 : latest.getVersionNo());
        } catch (Exception e) {
            log.warn("重置版本号计数器失败，appId: {}", appId, e);
        }
    }

    /**
     * 超出保留数量时，从最老的开始清理（当前版本永不清理）
     */
    private void prune(long appId) {
        int maxKeep = Math.max(1, properties.getMaxKeep());
        // 多取一些：prune 是「尽力而为」，一次最多清 50 个，避免极端情况下长时间占锁
        List<AppVersion> versions = this.list(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("versionNo", true));
        int overflow = versions.size() - maxKeep;
        if (overflow <= 0) {
            return;
        }
        int removed = 0;
        for (AppVersion version : versions) {
            if (removed >= overflow || removed >= 50) {
                break;
            }
            if (Integer.valueOf(1).equals(version.getIsCurrent())) {
                continue;
            }
            storage.delete(appId, version.getVersionNo());
            // 物理删：见 SEQ_KEY_PREFIX 的注释，版本号不依赖表内最大号，所以可以真删；
            // 走 LogicDeleteManager 是因为实体上配了逻辑删除，默认 removeById 只会置 isDelete=1
            LogicDeleteManager.execWithoutLogicDelete(() -> this.getMapper().deleteById(version.getId()));
            removed++;
        }
        if (removed > 0) {
            log.info("版本数量超过 {}，已清理最老的 {} 个，appId: {}", maxKeep, removed, appId);
        }
    }

    /**
     * 仅本人或管理员可操作
     */
    private void checkOwner(App app, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        boolean isOwner = app.getUserId() != null && app.getUserId().equals(loginUser.getId());
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        ThrowUtils.throwIf(!isOwner && !isAdmin, ErrorCode.NO_AUTH_ERROR, "无权限操作该应用的版本");
    }

    private static ReentrantLock lockOf(long appId) {
        return APP_LOCKS.computeIfAbsent(appId, key -> new ReentrantLock());
    }
}
