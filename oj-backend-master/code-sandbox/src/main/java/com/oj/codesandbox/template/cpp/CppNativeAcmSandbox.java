package com.oj.codesandbox.template.cpp;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.oj.codesandbox.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.pool.SandboxSlot;
import com.oj.codesandbox.support.StageTimer;
import com.oj.codesandbox.template.SandboxTemplate;

@Component("cppAcmSandbox")
// 只在开启native代码沙箱时加载
@ConditionalOnProperty(name = "oj.code-sandbox.type", havingValue = "native")
public class CppNativeAcmSandbox extends SandboxTemplate {

    @Override
    protected ExecuteCodeRes compileAndRun(SandboxSlot slot, File codeFile, List<String> inputList, StageTimer timer)
            throws IOException {
        // TODO: C++ 编译运行尚未实现（记在 docs/QUALITY_SCORE.md 的代码执行缺口）
        throw new UnsupportedOperationException("Unimplemented method 'compileAndRun'");
    }
}
