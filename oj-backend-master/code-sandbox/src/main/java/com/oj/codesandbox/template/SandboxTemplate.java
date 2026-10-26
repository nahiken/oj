package com.oj.codesandbox.template;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import com.oj.codesandbox.metrics.SandboxMetrics;
import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.model.enums.ExecuteCodeStatusEnum;
import com.oj.codesandbox.pool.SandboxBusyException;
import com.oj.codesandbox.pool.SandboxSlot;
import com.oj.codesandbox.pool.SandboxSlotPool;
import com.oj.codesandbox.support.StageTimer;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;

import cn.hutool.core.io.FileUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import lombok.extern.slf4j.Slf4j;

/**
 * 模板方法：借槽位 → 写代码 → 编译运行 → 归还（或销毁）。
 *
 * <p>与 P1 的差别：工作区不再每任务新建，而是从 {@link SandboxSlotPool} 借；
 * 阶段耗时写进 {@code stageMs} 供 judge 侧记账。契约见
 * {@code docs/design-docs/0002-judge-pool.md} §3。
 */
@Slf4j
@Component
@RefreshScope
public abstract class SandboxTemplate {
    private static final WordTree WORD_TREE;
    @Value("${oj.sandbox.java-xmx:128}")
    protected int javaXmx;
    @Value("${oj.sandbox.timeout:1000}")
    protected long timeout;
    @Value("${oj.sandbox.filename:Main}")
    protected String filename;

    @Resource
    private SandboxSlotPool slotPool;
    @Resource
    private SandboxMetrics sandboxMetrics;

    static {
        WORD_TREE = new WordTree();
        WORD_TREE.addWords("Files", "exec");
    }

    /**
     * 模板方法，定义运行步骤。子类不控制槽位生命周期。
     */
    public final ExecuteCodeRes executeCode(List<String> inputList, String code, String ext) {
        StageTimer timer = new StageTimer();
        ExecuteCodeRes response = null;
        SandboxSlot slot = null;
        boolean reusable = true;
        try {
            long acquireMark = timer.mark();
            try {
                slot = slotPool.borrow();
            } catch (SandboxBusyException e) {
                sandboxMetrics.recordBusy();
                log.warn("evt=sandbox.busy msg={}", e.getMessage());
                return ExecuteCodeRes.builder()
                        .code(ExecuteCodeStatusEnum.RUN_FAILED.getValue())
                        .msg("判题繁忙，请稍后重试")
                        .build();
            }
            timer.record("acquire", acquireMark);

            File codeFile = saveFile(code, slot.workspace().toFile(), ext);
            response = compileAndRun(slot, codeFile, inputList, timer);
        } catch (BusinessException e) {
            response = ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.COMPILE_FAILED.getValue())
                    .msg(e.getMessage())
                    .build();
        } catch (IOException | InterruptedException e) {
            log.error("代码编译运行失败，服务器内部错误：", e);
            reusable = false;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            response = ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.RUN_FAILED.getValue())
                    .msg("服务器内部错误")
                    .build();
        } catch (RuntimeException e) {
            // 契约 §3.2：执行期抛异常说明槽位状态未知（可能残留进程/半写文件），必须销毁而不是复用。
            // 只放行 BusinessException（用户代码被拦截/编译失败，工作区由 reset 清理干净）。
            log.error("代码执行出现未预期异常，槽位将销毁：", e);
            reusable = false;
            response = ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.RUN_FAILED.getValue())
                    .msg("服务器内部错误")
                    .build();
        } finally {
            long cleanupMark = timer.mark();
            if (slot != null) {
                try {
                    slotPool.release(slot, reusable);
                } catch (Exception e) {
                    log.warn("evt=sandbox.release_failed slot={}", slot.id(), e);
                }
            }
            timer.record("cleanup", cleanupMark);
            if (response != null) {
                response.setStageMs(timer.snapshot());
                sandboxMetrics.recordStages(response.getStageMs());
            }
        }
        return response;
    }

    /**
     * 将 code 保存在 dir 目录下。
     */
    protected File saveFile(String code, File dir, String ext) {
        FoundWord foundWord = WORD_TREE.matchWord(code);
        if (foundWord != null) {
            throw new BusinessException(HttpCodeEnum.BAD_REQUEST, "危险代码");
        }

        FileUtil.mkdir(dir);
        File codeFile = new File(dir, this.filename + ext);
        log.debug("代码保存路径：{}", codeFile.getAbsolutePath());
        FileUtil.writeUtf8String(code, codeFile.getAbsolutePath());

        // POSIX 权限只在类 Unix 系统上设置；Windows 不支持 setPosixFilePermissions
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                Set<PosixFilePermission> permissions = new HashSet<>(Arrays.asList(PosixFilePermission.values()));
                Files.setPosixFilePermissions(Paths.get(dir.getAbsolutePath()), permissions);
            } catch (IOException e) {
                throw new BusinessException(HttpCodeEnum.INTERNAL_SERVER_ERROR, "文件权限设置失败");
            }
        }

        return codeFile;
    }

    /**
     * 编译并运行 codeFile；slot 提供工作区/容器，timer 记录 compile/run 阶段耗时。
     */
    protected abstract ExecuteCodeRes compileAndRun(SandboxSlot slot, File codeFile, List<String> inputList,
            StageTimer timer) throws IOException, InterruptedException;
}
