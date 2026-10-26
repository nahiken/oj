package com.oj.codesandbox.pool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;

/**
 * docker 槽位的健康语义：inspect 只能证明容器在跑，写探针才能证明容器里的 /app 还能写。
 *
 * <p>用 mock 的 DockerClient 钉住「谁决定 healthy / probe」，真容器验证放在
 * {@code benchmark/run-p2.ps1 -Scenario pool-docker}（契约 §3.3）。
 */
class DockerContainerSlotTest {

    private static final String CONTAINER_ID = "container-1";

    @TempDir
    Path tempDir;

    private DockerClient dockerClient;
    private InspectContainerCmd inspectContainerCmd;
    private InspectContainerResponse inspectContainerResponse;
    private ContainerState containerState;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        inspectContainerCmd = mock(InspectContainerCmd.class);
        inspectContainerResponse = mock(InspectContainerResponse.class);
        containerState = mock(ContainerState.class);
        when(dockerClient.inspectContainerCmd(CONTAINER_ID)).thenReturn(inspectContainerCmd);
        when(inspectContainerCmd.exec()).thenReturn(inspectContainerResponse);
        when(inspectContainerResponse.getState()).thenReturn(containerState);
        when(containerState.getRunning()).thenReturn(true);
    }

    @Test
    void healthyWhenContainerRunsAndWorkspaceExists() {
        DockerContainerSlot slot = newSlot(tempDir);
        assertTrue(slot.healthy());
    }

    @Test
    void unhealthyWhenWorkspaceDisappeared() {
        DockerContainerSlot slot = newSlot(tempDir.resolve("missing"));
        assertFalse(slot.healthy());
    }

    @Test
    void unhealthyWhenContainerStopped() {
        when(containerState.getRunning()).thenReturn(false);
        DockerContainerSlot slot = newSlot(tempDir);
        assertFalse(slot.healthy());
    }

    @Test
    void probeDoesNotExecWhenContainerStopped() {
        // 容器没在跑时不必再开一次 exec：probe 直接跟 healthy 一起返回 false。
        // 必须断言「没调 exec」——只断言 false 的话，exec 抛异常被 catch 成 false 也能通过。
        when(containerState.getRunning()).thenReturn(false);
        DockerContainerSlot slot = newSlot(tempDir);
        assertFalse(slot.probe());
        verify(dockerClient, never()).execCreateCmd(any());
    }

    @Test
    void probeSucceedsWhenContainerCanWriteWorkspace() {
        stubProbeExitCode(0L);
        DockerContainerSlot slot = newSlot(tempDir);
        assertTrue(slot.probe());
    }

    @Test
    void probeFailsWhenContainerCannotWriteWorkspace() {
        // 容器在跑、宿主目录也在，但容器内写 /app 失败（bind mount 丢失 / 去掉了写权限）
        stubProbeExitCode(1L);
        DockerContainerSlot slot = newSlot(tempDir);
        assertTrue(slot.healthy());
        assertFalse(slot.probe());
    }

    @Test
    void probeFailsWhenExecInspectIsUnavailable() {
        stubProbeExitCode(null);
        DockerContainerSlot slot = newSlot(tempDir);
        assertFalse(slot.probe());
    }

    @Test
    void resetRefusesStoppedContainer() {
        when(containerState.getRunning()).thenReturn(false);
        DockerContainerSlot slot = newSlot(tempDir);
        assertThrows(IllegalStateException.class, slot::reset);
    }

    private DockerContainerSlot newSlot(Path workspace) {
        return new DockerContainerSlot("pool-1", workspace, CONTAINER_ID, dockerClient);
    }

    private void stubProbeExitCode(Long exitCode) {
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class);
        when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.withAttachStdout(anyBoolean())).thenReturn(createCmd);
        when(createCmd.withAttachStderr(anyBoolean())).thenReturn(createCmd);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-1");
        when(createCmd.exec()).thenReturn(createResponse);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd("exec-1")).thenReturn(startCmd);
        ResultCallback.Adapter<Frame> callback = mock(ResultCallback.Adapter.class);
        when(startCmd.exec(any())).thenReturn(callback);

        InspectExecCmd inspectExecCmd = mock(InspectExecCmd.class);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectExecCmd);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(exitCode);
        when(inspectExecCmd.exec()).thenReturn(inspectExecResponse);
    }
}
