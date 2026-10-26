package com.oj.codesandbox.pool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * native 后端的槽位：只复用一个宿主目录。
 */
@Slf4j
public class NativeWorkspaceSlot implements SandboxSlot {

    private final String id;
    private final Path workspace;
    private int useCount;

    public NativeWorkspaceSlot(String id, Path workspace) {
        this.id = id;
        this.workspace = workspace;
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
        return null;
    }

    @Override
    public int useCount() {
        return useCount;
    }

    @Override
    public void markUsed() {
        useCount++;
    }

    /**
     * Windows 上 {@code Files.isWritable(dir)} 对目录不可靠（实测总是 false，但目录实际可写），
     * 所以用「写一个探针文件再删掉」判断，跨平台一致。
     */
    @Override
    public boolean healthy() {
        if (!Files.isDirectory(workspace)) {
            log.warn("evt=sandbox.slot.not_directory slot={} workspace={}", id, workspace.toAbsolutePath());
            return false;
        }
        Path probe = workspace.resolve(".healthy-probe");
        try {
            Files.write(probe, new byte[] { 1 });
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            log.warn("evt=sandbox.slot.probe_failed slot={} workspace={}", id, workspace.toAbsolutePath(), e);
            return false;
        }
    }

    @Override
    public void reset() {
        FileUtil.clean(workspace.toFile());
    }

    @Override
    public void destroy() {
        FileUtil.del(workspace.toFile());
    }
}
