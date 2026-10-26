package com.oj.codesandbox.service;

import com.oj.codesandbox.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.model.dto.ExecuteCodeRes;

public interface SandboxService {
    ExecuteCodeRes execute(ExecuteCodeReq executeCodeRequest);
}
