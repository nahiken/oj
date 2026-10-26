package com.oj.codesandbox.template.java;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.model.dto.ExecuteResult;
import com.oj.codesandbox.model.enums.ExecuteCodeStatusEnum;
import com.oj.codesandbox.model.enums.ExitCodeEnum;
import com.oj.codesandbox.pool.SandboxSlot;
import com.oj.codesandbox.support.StageTimer;
import com.oj.codesandbox.template.SandboxTemplate;
import com.oj.codesandbox.utils.ProcessUtils;

import cn.hutool.core.collection.CollectionUtil;
import lombok.extern.slf4j.Slf4j;

@Component("javaAcmSandbox")
// 只在开启native代码沙箱时加载
@ConditionalOnProperty(name = "oj.code-sandbox.type", havingValue = "native")
@Slf4j
public class JavaNativeAcmSandbox extends SandboxTemplate {

    @Override
    protected ExecuteCodeRes compileAndRun(SandboxSlot slot, File codeFile, List<String> inputList, StageTimer timer)
            throws IOException {
        // 1. 编译代码（工作区由池提供，编译产物留在同一目录）
        String[] compileCmd = { "javac", "-encoding", "utf-8", codeFile.getAbsolutePath() };
        log.debug("编译命令：{}", Arrays.toString(compileCmd));

        long compileMark = timer.mark();
        Process compileProcess = Runtime.getRuntime().exec(compileCmd);
        ExecuteResult compileResult = ProcessUtils.getProcessMsg(compileProcess);
        timer.record("compile", compileMark);
        if (compileResult.getExitCode() != 0) {
            return ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.COMPILE_FAILED.getValue())
                    .msg(compileResult.getStderr().replaceAll(codeFile.getParent(), ""))
                    .build();
        }

        // 2. 执行代码
        List<ExecuteResult> executeResults = new ArrayList<>();

        if (CollectionUtil.isEmpty(inputList)) {
            inputList = new ArrayList<>();
            inputList.add(null);
        }

        for (String input : inputList) {
            long runMark = timer.mark();
            ExecuteResult executeResult = runOneCase(codeFile, input);
            timer.record("run", runMark);
            executeResults.add(executeResult);

            // 已经有用例失败了
            if (executeResult.getExitCode() != ExitCodeEnum.SUCCESS.getValue()) {
                ExecuteCodeStatusEnum statusEnum = ExecuteCodeStatusEnum
                        .getEnumByExitCodeEnum(ExitCodeEnum.getEnumByValue(executeResult.getExitCode()));
                if (statusEnum == null) {
                    statusEnum = ExecuteCodeStatusEnum.RUN_FAILED;
                }
                return ExecuteCodeRes.builder()
                        .code(statusEnum.getValue())
                        .msg(executeResult.getStderr())
                        .results(executeResults)
                        .build();
            }
        }

        return ExecuteCodeRes.builder()
                .code(ExecuteCodeStatusEnum.SUCCESS.getValue())
                .msg(ExecuteCodeStatusEnum.SUCCESS.getMsg())
                .results(executeResults)
                .build();
    }

    public ExecuteResult runOneCase(File codeFile, String input) throws IOException {
        String[] runCmd = { "java", String.format("-Xmx%dm", this.javaXmx), "-Dfile.encoding=UTF-8",
                "-cp", codeFile.getParent(), "Main" };

        long start = System.currentTimeMillis();

        Process runProcess = Runtime.getRuntime().exec(runCmd);
        // 超时控制
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(this.timeout);
                // 超时了，直接杀死运行代码的进程
                runProcess.destroy();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        ExecuteResult executeResult;
        if (input == null) {
            executeResult = ProcessUtils.getProcessMsg(runProcess);
        } else {
            executeResult = ProcessUtils.getProcessMessage(runProcess, input);
        }

        long end = System.currentTimeMillis();
        executeResult.setTime(end - start);
        // native 沙箱没有内存监控，给 0 兜底，避免判题侧拆箱 NPE
        if (executeResult.getMemory() == null) {
            executeResult.setMemory(0L);
        }

        if (!thread.isAlive()) {
            executeResult = new ExecuteResult();
            executeResult.setExitCode(ExitCodeEnum.TIMEOUT.getValue());
            executeResult.setStderr("超出时间限制");
            executeResult.setMemory(0L);
        }

        return executeResult;
    }
}
