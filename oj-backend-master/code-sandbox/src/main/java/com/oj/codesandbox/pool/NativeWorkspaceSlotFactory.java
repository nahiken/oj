package com.oj.codesandbox.pool;

import java.nio.file.Files;
import java.nio.file.Path;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * native 槽位工厂：在根目录下建工作区。
 *
 * <p>mkdir 失败必须立刻炸掉并打印路径：静默失败会让池一直「不健康」，
 * 表现为判题全部超时（P2 调试时踩过：池根目录来自 docker save-path，指向不存在的路径）。
 */
@Slf4j
public class NativeWorkspaceSlotFactory implements SandboxSlotFactory {

    private final Path root;

    public NativeWorkspaceSlotFactory(Path root) {
        this.root = root;
        FileUtil.mkdir(root.toFile());
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("沙箱池根目录创建失败: " + root.toAbsolutePath());
        }
        log.info("evt=sandbox.pool.root path={}", root.toAbsolutePath());
    }

    @Override
    public SandboxSlot create(String slotId) {
        Path workspace = root.resolve(slotId);
        FileUtil.mkdir(workspace.toFile());
        if (!Files.isDirectory(workspace)) {
            throw new IllegalStateException("沙箱工作区创建失败: " + workspace.toAbsolutePath());
        }
        return new NativeWorkspaceSlot(slotId, workspace);
    }

    @Override
    public String backend() {
        return "native";
    }
}
