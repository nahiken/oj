package com.oj.codesandbox.pool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * docker 槽位工厂：创建常驻容器（只读根文件系统、无网络、cap-drop、pids 限制、非 root）。
 *
 * <p>容器创建发生在池预热/重建时，不在判题请求的关键路径上——这是 P2 要拿掉的冷启动成本。
 */
@Slf4j
public class DockerContainerSlotFactory implements SandboxSlotFactory {

    private final DockerClient dockerClient;
    private final Path workspaceRoot;
    private final String memScript;
    private final String image;
    private final long memoryLimitMb;
    private final long cpuCount;

    public DockerContainerSlotFactory(DockerClient dockerClient, Path workspaceRoot, String memScript, String image,
            long memoryLimitMb, long cpuCount) {
        this.dockerClient = dockerClient;
        this.workspaceRoot = workspaceRoot;
        this.memScript = resolveMemScript(memScript);
        this.image = image;
        this.memoryLimitMb = memoryLimitMb;
        this.cpuCount = cpuCount;
        FileUtil.mkdir(workspaceRoot.toFile());
        log.info("evt=sandbox.slot.factory backend=docker image={} memScript={} workspaceRoot={}", image, this.memScript,
                workspaceRoot.toAbsolutePath());
        // 相对路径按进程工作目录解析，从错的目录启动就会指到不存在的文件，症状是创建容器的
        // 写探针失败、沙箱整个起不来。这里提前喊一声（只 warn：docker daemon 在别的机器上时，
        // 这个路径由那台机器解析，本机看不到是正常）。
        if (this.memScript != null && !Files.isRegularFile(Paths.get(this.memScript))) {
            log.warn("evt=sandbox.slot.mem_script_missing path={} hint=相对路径按沙箱进程工作目录解析；"
                    + "从 .runtime/run/sandbox 启动时用 ../../../oj-backend-master/script/mem.sh",
                    this.memScript);
        }
    }

    /**
     * 配置里的 mem-script 允许写相对路径（模板不写某台机器的绝对路径），
     * 但 docker 的 bind mount 只认绝对路径，这里按进程工作目录解析。
     */
    private static String resolveMemScript(String memScript) {
        if (memScript == null || memScript.isBlank()) {
            return memScript;
        }
        return Paths.get(memScript).toAbsolutePath().normalize().toString();
    }

    @Override
    public SandboxSlot create(String slotId) {
        Path workspace = workspaceRoot.resolve(slotId);
        FileUtil.mkdir(workspace.toFile());

        HostConfig hostConfig = new HostConfig()
                .withReadonlyRootfs(true)
                .withNetworkMode("none")
                .withPidsLimit(64L)
                .withCapDrop(Capability.ALL)
                .withMemory(memoryLimitMb * 1024L * 1024L)
                .withMemorySwap(0L)
                .withCpuCount(cpuCount)
                .withBinds(new Bind(workspace.toAbsolutePath().toString(), new Volume("/app")),
                        new Bind(memScript, new Volume("/script/mem.sh")));

        // 容器名就是槽位名（pool-1..N / ephemeral-N）。进程被 kill -9 时容器不会跟着消失，
        // 下次启动 create 会因重名 409 直接失败、整个服务起不来。先按名字删一遍，删不到就是没有。
        removeIfExists(slotId);

        CreateContainerResponse response = dockerClient.createContainerCmd(image)
                .withName(slotId)
                .withHostConfig(hostConfig)
                .withUser("nobody")
                .withTty(true)
                .exec();
        dockerClient.startContainerCmd(response.getId()).exec();
        DockerContainerSlot slot = new DockerContainerSlot(slotId, workspace, response.getId(), dockerClient);
        // 创建即验证：容器能写 /app 才说明 bind mount 与 mem-script 路径都对。
        // 留到第一次判题才发现，会把配置错误伪装成「用户代码运行失败」。
        if (!slot.probe()) {
            slot.destroy();
            throw new IllegalStateException("docker 槽位写探针失败：容器内无法写 /app，检查 bind mount 与 mem-script 路径（"
                    + memScript + "）");
        }
        log.info("evt=sandbox.slot.created slot={} container={} image={} workspace={} probe=ok", slotId,
                response.getId(), image, workspace);
        return slot;
    }

    @Override
    public String backend() {
        return "docker";
    }

    private void removeIfExists(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
            log.warn("evt=sandbox.slot.stale_removed name={}", name);
        } catch (NotFoundException e) {
            // 正常路径：没有同名容器
        } catch (Exception e) {
            log.warn("evt=sandbox.slot.stale_remove_failed name={}", name, e);
        }
    }
}
