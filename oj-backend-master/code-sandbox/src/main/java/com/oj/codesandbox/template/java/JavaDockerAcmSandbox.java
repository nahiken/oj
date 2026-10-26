package com.oj.codesandbox.template.java;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.model.dto.ExecuteResult;
import com.oj.codesandbox.model.enums.ExecuteCodeStatusEnum;
import com.oj.codesandbox.model.enums.ExitCodeEnum;
import com.oj.codesandbox.pool.SandboxSlot;
import com.oj.codesandbox.support.StageTimer;
import com.oj.codesandbox.template.SandboxTemplate;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * docker 沙箱：容器由 {@code DockerContainerSlotFactory} 在池预热时创建，这里只借用。
 *
 * <p>P1 是「每任务建容器 → 判完 stop/remove」，容器创建在关键路径上；
 * P2 改成常驻容器 + 固定 bind mount，本类不再负责容器生命周期。
 *
 * <p><b>不要加 {@code @RefreshScope}</b>：它给 bean 套 CGLIB 代理，而
 * {@code SandboxTemplate.executeCode} 是 {@code final}（子类不许控制槽位生命周期），
 * 代理无法覆盖 final 方法，调用会落在**没有注入过字段的代理实例**上，
 * 表现是 `slotPool` / `sandboxMetrics` 全为 null。另外三个模板本来就没有这个注解，
 * 改超时/池参数统一走重启，和 native 后端一致。
 */
@Component("javaAcmSandbox")
// 只在开启docker代码沙箱时加载
@ConditionalOnProperty(name = "oj.code-sandbox.type", havingValue = "docker")
@Slf4j
public class JavaDockerAcmSandbox extends SandboxTemplate {

    @Resource
    private DockerClient dockerClient;

    @Override
    protected ExecuteCodeRes compileAndRun(SandboxSlot slot, File codeFile, List<String> inputList, StageTimer timer)
            throws IOException, InterruptedException {
        String containerId = slot.containerId();
        if (containerId == null) {
            throw new IllegalStateException("docker 沙箱借到的槽位没有容器: " + slot.id());
        }

        String stdout;
        String stderr;

        // 1. 编译 Main.java
        long compileMark = timer.mark();
        String compileExecId = dockerClient.execCreateCmd(containerId)
                .withCmd("javac", "-d", "/app", "/app/Main.java")
                .withAttachStderr(true)
                .withAttachStdout(true)
                .exec()
                .getId();

        ByteArrayOutputStream compileStdout = new ByteArrayOutputStream();
        ByteArrayOutputStream compileStderr = new ByteArrayOutputStream();

        dockerClient.execStartCmd(compileExecId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        compileStdout.write(frame.getPayload());
                    } else if (frame.getStreamType() == StreamType.STDERR) {
                        compileStderr.write(frame.getPayload());
                    }
                } catch (IOException e) {
                    log.warn("evt=sandbox.compile.read_failed", e);
                }
            }
        }).awaitCompletion();
        timer.record("compile", compileMark);

        stdout = compileStdout.toString("UTF-8").trim();
        stderr = compileStderr.toString("UTF-8").trim();
        log.debug("编译标准输出: {}, 编译错误输出: {}", stdout, stderr);
        if (StrUtil.isNotBlank(stderr)) {
            return ExecuteCodeRes.builder()
                    .code(ExecuteCodeStatusEnum.COMPILE_FAILED.getValue())
                    .msg(stderr)
                    .build();
        }

        // 2. 运行编译后的 Java 文件，并传入输入数据
        List<ExecuteResult> executeResults = new ArrayList<>();

        if (CollectionUtil.isEmpty(inputList)) {
            inputList = new ArrayList<>();
            inputList.add(null);
        }

        for (String input : inputList) {
            long runMark = timer.mark();
            // 3. 内存监控
            AtomicLong maxMemoryUsage = new AtomicLong(0L);
            ExecCreateCmdResponse memResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd("/bin/sh", "/script/mem.sh", "0.01")
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String memExecId = memResponse.getId();

            PipedOutputStream memInputPipe = new PipedOutputStream();
            PipedInputStream memInput = new PipedInputStream(memInputPipe);
            ByteArrayOutputStream memStdout = new ByteArrayOutputStream();
            ByteArrayOutputStream memStderr = new ByteArrayOutputStream();

            dockerClient.execStartCmd(memExecId).withStdIn(memInput).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        if (frame.getStreamType() == StreamType.STDOUT) {
                            memStdout.write(frame.getPayload());
                            maxMemoryUsage
                                    .set(Math.max(maxMemoryUsage.get(),
                                            Long.valueOf(new String(frame.getPayload()).trim())));
                        } else if (frame.getStreamType() == StreamType.STDERR) {
                            memStderr.write(frame.getPayload());
                        }
                    } catch (IOException e) {
                        log.warn("evt=sandbox.mem.read_failed", e);
                    }
                }
            });

            ExecCreateCmdResponse runResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd("java", String.format("-Xmx%dm", this.javaXmx), "-cp", "/app", "Main")
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String runExecId = runResponse.getId();

            PipedOutputStream runInputPipe = new PipedOutputStream();
            PipedInputStream runInput = new PipedInputStream(runInputPipe);
            ByteArrayOutputStream runStdout = new ByteArrayOutputStream();
            ByteArrayOutputStream runStderr = new ByteArrayOutputStream();

            if (input != null) {
                runInputPipe.write(input.getBytes());
                runInputPipe.write("\n".getBytes());
                runInputPipe.flush();
                runInputPipe.close();
            }

            long startTime = System.currentTimeMillis();

            ResultCallback.Adapter<Frame> callback = dockerClient.execStartCmd(runExecId).withStdIn(runInput)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            try {
                                if (frame.getStreamType() == StreamType.STDOUT) {
                                    runStdout.write(frame.getPayload());
                                } else if (frame.getStreamType() == StreamType.STDERR) {
                                    runStderr.write(frame.getPayload());
                                }
                            } catch (IOException e) {
                                log.warn("evt=sandbox.run.read_failed", e);
                            }
                        }
                    });

            // 超时控制
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(this.timeout);
                    callback.close();
                } catch (InterruptedException e) {
                    log.debug("超时控制线程终止，代码在时间限制内运行完成");
                } catch (IOException e) {
                    log.warn("evt=sandbox.timeout.close_failed", e);
                }
            });
            thread.start();

            callback.awaitCompletion();
            long elapsedTime = System.currentTimeMillis() - startTime;
            timer.record("run", runMark);

            // 关闭内存统计：mem.sh 是 POSIX sh（按行读），必须带换行才能让它读到 "q"
            memInputPipe.write("q\n".getBytes());
            memInputPipe.flush();
            memInputPipe.close();

            stdout = runStdout.toString("UTF-8").trim();
            stderr = runStderr.toString("UTF-8").trim();
            log.debug("运行标准输出: {}, 运行错误输出: {}, 耗时: {} ms, 内存占用: {} bytes", stdout, stderr, elapsedTime,
                    maxMemoryUsage.get());

            ExecuteResult executeResult = ExecuteResult.builder()
                    .exitCode(ExitCodeEnum.SUCCESS.getValue())
                    .stdout(stdout)
                    .stderr(stderr)
                    .time(elapsedTime)
                    .memory(maxMemoryUsage.get())
                    .build();

            if (!thread.isAlive()) {
                executeResult.setExitCode(ExitCodeEnum.TIMEOUT.getValue());
                executeResult.setStderr("超出时间限制");
            }

            thread.interrupt();

            if (StrUtil.isNotBlank(stderr)) {
                executeResult.setExitCode(ExitCodeEnum.RUN_FAILED.getValue());
            }

            executeResults.add(executeResult);

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
}
