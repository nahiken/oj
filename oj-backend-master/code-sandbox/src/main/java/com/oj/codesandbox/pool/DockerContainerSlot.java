package com.oj.codesandbox.pool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * docker 槽位：常驻容器 + 固定 bind mount。
 *
 * <p>归还时清空宿主工作区（容器 /app）并杀掉容器内残留的 java/javac；
 * 容器健康检查失败或清理失败时由池销毁重建，避免复用串代码。
 *
 * <p>健康检查分两级：{@link #healthy()} 走借约热路径（宿主目录 + 一次 inspect），
 * {@link #probe()} 走周期巡检与创建后验证（再多一次容器内写探针）。
 */
@Slf4j
public class DockerContainerSlot implements SandboxSlot {

    /** 容器内写探针：readonlyRootfs 下只有 /app 可写，探针能写说明 bind mount 仍然有效。 */
    private static final String PROBE_CMD = "printf 1 > /app/.health-probe && rm -f /app/.health-probe";

    /**
     * 清理残留进程。镜像不保证有 pkill/pgrep（jenkins/agent 就没有），
     * 所以直接扫 /proc 的 comm，只杀用户代码会产生的 java/javac，不碰 exec 自己的 sh。
     */
    private static final String KILL_CMD = "for d in /proc/[0-9]*; do "
            + "c=$(cat \"$d/comm\" 2>/dev/null) || continue; "
            + "case \"$c\" in java|javac) kill -9 \"${d#/proc/}\" 2>/dev/null ;; esac; "
            + "done; exit 0";

    private final String id;
    private final Path workspace;
    private final String containerId;
    private final DockerClient dockerClient;
    private int useCount;

    public DockerContainerSlot(String id, Path workspace, String containerId, DockerClient dockerClient) {
        this.id = id;
        this.workspace = workspace;
        this.containerId = containerId;
        this.dockerClient = dockerClient;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public String containerId() {
        return containerId;
    }

    @Override
    public int useCount() {
        return useCount;
    }

    @Override
    public void markUsed() {
        useCount++;
    }

    @Override
    public boolean healthy() {
        try {
            if (!Files.isDirectory(workspace)) {
                return false;
            }
            return running();
        } catch (Exception e) {
            log.warn("evt=sandbox.slot.inspect_failed slot={} container={}", id, containerId, e);
            return false;
        }
    }

    @Override
    public boolean probe() {
        if (!healthy()) {
            return false;
        }
        try {
            long exitCode = exec("sh", "-c", PROBE_CMD);
            boolean ok = exitCode == 0L;
            if (!ok) {
                log.warn("evt=sandbox.slot.probe_failed slot={} container={} exitCode={}", id, containerId, exitCode);
            }
            return ok;
        } catch (Exception e) {
            log.warn("evt=sandbox.slot.probe_error slot={} container={}", id, containerId, e);
            return false;
        }
    }

    @Override
    public void reset() {
        // 契约 §3.2：归还时容器必须 running，否则抛出让池销毁重建
        if (!running()) {
            throw new IllegalStateException("sandbox container is not running: " + containerId);
        }
        // bind mount 是宿主目录，直接删内容比 exec rm 快且不依赖镜像里的 shell 工具
        FileUtil.clean(workspace.toFile());
        killLeftoverProcesses();
    }

    @Override
    public void destroy() {
        // 直接 force remove，不走「先 stop 等 3s」：容器主进程是 withTty(true) 起的 shell，
        // 不响应 SIGTERM，优雅停机固定吃掉 3s（实测 ephemeral 每次判题多 3.3s），而且槽位
        // 本来就是要丢掉的、没有任何状态需要 flush。
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (NotFoundException e) {
            log.debug("evt=sandbox.slot.already_gone container={}", containerId);
        } catch (Exception e) {
            log.warn("evt=sandbox.slot.remove_failed container={}", containerId, e);
        }
        FileUtil.del(workspace.toFile());
    }

    private boolean running() {
        return Boolean.TRUE.equals(dockerClient.inspectContainerCmd(containerId).exec().getState().getRunning());
    }

    /**
     * 在容器里跑一条命令并等它结束，返回退出码（拿不到退出码时 -1）。
     *
     * <p>docker-java 的 exec 是三段式（create → start → inspect），探针和清残留进程
     * 都要走一遍，抽在这里免得两处各自漂移（其中一处忘了看退出码）。
     */
    private long exec(String... cmd) throws InterruptedException {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>())
                .awaitCompletion(3, TimeUnit.SECONDS);
        Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        return exitCode == null ? -1L : exitCode;
    }

    private void killLeftoverProcesses() {
        try {
            long exitCode = exec("sh", "-c", KILL_CMD);
            if (exitCode != 0L) {
                log.warn("evt=sandbox.slot.kill_nonzero slot={} container={} exitCode={}", id, containerId, exitCode);
            }
        } catch (Exception e) {
            log.warn("evt=sandbox.slot.kill_failed slot={} container={}", id, containerId, e);
        }
    }
}
