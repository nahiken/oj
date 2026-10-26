package com.oj.codesandbox.pool;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.dockerjava.api.DockerClient;

/**
 * 沙箱池装配。
 *
 * <p>一个沙箱进程一个池：native 时槽位是通用工作区，docker 时槽位是常驻容器（当前只有 Java 镜像）。
 * {@code oj.sandbox.pool.enabled=false} 时换成一次性池，行为退回 P1。
 */
@Configuration
public class SandboxPoolConfig {

    @Bean
    public SandboxSlotFactory sandboxSlotFactory(
            @Value("${oj.code-sandbox.type:native}") String type,
            @Value("${oj.sandbox.pool.root:}") String poolRoot,
            @Value("${oj.code-sandbox.docker.save-path:}") String dockerSavePath,
            @Value("${oj.code-sandbox.docker.mem-script:}") String memScript,
            @Value("${oj.code-sandbox.docker.jdk-image:openjdk:8-alpine}") String jdkImage,
            @Value("${oj.code-sandbox.docker.memory-limit:128}") long memoryLimitMb,
            @Value("${oj.code-sandbox.docker.cpu-count:1}") long cpuCount,
            ObjectProvider<DockerClient> dockerClientProvider) {

        Path workspaceRoot = resolveWorkspaceRoot(type, poolRoot, dockerSavePath);
        if ("docker".equalsIgnoreCase(type)) {
            DockerClient dockerClient = dockerClientProvider.getIfAvailable();
            if (dockerClient == null) {
                throw new IllegalStateException("oj.code-sandbox.type=docker 但没有 DockerClient bean");
            }
            return new DockerContainerSlotFactory(dockerClient, workspaceRoot, memScript, jdkImage, memoryLimitMb,
                    cpuCount);
        }
        return new NativeWorkspaceSlotFactory(workspaceRoot);
    }

    @Bean
    public SandboxSlotPool sandboxSlotPool(SandboxSlotFactory factory, SandboxPoolProperties properties) {
        if (properties.isEnabled()) {
            return new ReusableSlotPool(factory, properties);
        }
        return new EphemeralSlotPool(factory);
    }

    /**
     * 池工作区根目录。
     *
     * <p>优先级：{@code oj.sandbox.pool.root} > docker 的 save-path（仅 docker 后端）> {@code user.dir/tmpCode/pool}。
     * 不要无条件复用 docker 的 save-path：它来自部署环境（Nacos），在别的机器上可能根本不存在，
     * 曾经导致所有槽位 mkdir 失败、池永远「不健康」。
     */
    private Path resolveWorkspaceRoot(String type, String poolRoot, String dockerSavePath) {
        if (poolRoot != null && !poolRoot.isBlank()) {
            return Paths.get(poolRoot);
        }
        if ("docker".equalsIgnoreCase(type) && dockerSavePath != null && !dockerSavePath.isBlank()) {
            return Paths.get(dockerSavePath);
        }
        return Paths.get(System.getProperty("user.dir"), "tmpCode", "pool");
    }
}
