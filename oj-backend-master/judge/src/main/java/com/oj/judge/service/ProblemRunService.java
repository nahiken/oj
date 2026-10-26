package com.oj.judge.service;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.judge.model.dto.problem.ProblemRunReq;

public interface ProblemRunService {
    ExecuteCodeRes doProblemRun(ProblemRunReq request);
}
