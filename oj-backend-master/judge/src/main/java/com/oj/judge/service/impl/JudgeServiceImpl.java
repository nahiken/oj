package com.oj.judge.service.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oj.codesandbox.sdk.model.dto.ExecuteCodeReq;
import com.oj.codesandbox.sdk.model.dto.ExecuteCodeRes;
import com.oj.codesandbox.sdk.model.enums.ExecuteCodeStatusEnum;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.judge.codesandbox.CodeSandbox;
import com.oj.judge.codesandbox.CodeSandboxFactory;
import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.mapper.ProblemSubmitMapper;
import com.oj.judge.model.dto.problem.JudgeCase;
import com.oj.judge.model.dto.problem.JudgeConfig;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.JudgeInfoEnum;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.model.vo.problemsubmit.JudgeInfo;
import com.oj.judge.model.vo.problemsubmit.ProblemSubmitVo;
import com.oj.judge.service.JudgeService;
import com.oj.judge.service.strategy.ResStrategyFactory;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class JudgeServiceImpl implements JudgeService {
    @Resource
    private ProblemMapper problemMapper;
    @Resource
    private ProblemSubmitMapper problemSubmitMapper;
    @Resource
    private CodeSandboxFactory codeSandboxFactory;
    @Resource
    private ResStrategyFactory resStrategyFactory;

    @Value("${oj.judge.api-provider:remote}")
    private String apiProvider;

    @Override
    public ProblemSubmitVo doJudge(ProblemSubmit problemSubmit, String accessKey, String secretKey) {
        // 1、更改判题（题目提交）的状态为"判题中"，防止重复执行
        ProblemSubmit updateSubmit = new ProblemSubmit();
        updateSubmit.setId(problemSubmit.getId());
        updateSubmit.setStatus(ProblemSubmitStatusEnum.RUNNING.getValue());
        int update = problemSubmitMapper.updateById(updateSubmit);
        ThrowUtils.throwIf(update == 0, HttpCodeEnum.INTERNAL_SERVER_ERROR, "题目状态更新失败");

        // 2、调用沙箱并转换成 JudgeInfo（同步链路没有 MQ 消息，自己生成 traceId 以便与提交日志串联）
        String traceId = UUID.randomUUID().toString().replace("-", "");
        JudgeInfo judgeInfo = executeJudge(problemSubmit, accessKey, secretKey, traceId);

        // 3、修改数据库中的判题结果
        boolean submitStatus = judgeInfo.getStatus().equals(JudgeInfoEnum.ACCEPTED.getValue());
        updateSubmit.setStatus(
                submitStatus ? ProblemSubmitStatusEnum.SUCCEED.getValue() : ProblemSubmitStatusEnum.FAILED.getValue());
        updateSubmit.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        update = problemSubmitMapper.updateById(updateSubmit);
        ThrowUtils.throwIf(update == 0, HttpCodeEnum.INTERNAL_SERVER_ERROR, "题目状态更新失败");

        // 4、修改题目的通过数
        if (submitStatus) {
            problemMapper.update(null, new UpdateWrapper<Problem>()
                    .setSql("accepted_num = accepted_num + 1").eq("id", problemSubmit.getProblemId()));
        }

        ProblemSubmitVo vo = BeanUtil.copyProperties(problemSubmit, ProblemSubmitVo.class, "status", "judgeInfo");
        vo.setStatus(updateSubmit.getStatus());
        vo.setJudgeInfo(judgeInfo);
        return vo;
    }

    @Override
    public JudgeInfo executeJudge(ProblemSubmit problemSubmit, String accessKey, String secretKey) {
        return executeJudge(problemSubmit, accessKey, secretKey, null);
    }

    @Override
    public JudgeInfo executeJudge(ProblemSubmit problemSubmit, String accessKey, String secretKey, String traceId) {
        // 1、获取对应的problem（不查 content/answer 大字段）
        Problem problem = problemMapper.selectOne(new QueryWrapper<Problem>()
                .select(Problem.class,
                        item -> !item.getColumn().equals("content")
                                && !item.getColumn().equals("answer"))
                .lambda().eq(Problem::getId, problemSubmit.getProblemId()));
        ThrowUtils.throwIf(problem == null, HttpCodeEnum.NOT_EXIST, "题目不存在");

        // 2、调用沙箱，获取到执行结果
        CodeSandbox codeSandbox = codeSandboxFactory.newInstance(apiProvider);
        // 获取输入用例
        List<JudgeCase> judgeCaseList = JSONUtil.toList(problem.getJudgeCase(), JudgeCase.class);
        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        // 期望输出
        List<String> expectedOutput = judgeCaseList.stream().map(judgeCase -> judgeCase.getOutput().trim())
                .collect(Collectors.toList());
        // 判题配置
        JudgeConfig judgeConfig = JSONUtil.toBean(problem.getJudgeConfig(), JudgeConfig.class);
        ExecuteCodeReq executeCodeRequest = ExecuteCodeReq.builder()
                .code(problemSubmit.getCode())
                .language(problemSubmit.getLanguage())
                .inputList(inputList)
                .build();
        ExecuteCodeRes response = codeSandbox.executeCode(executeCodeRequest, accessKey, secretKey);

        // 沙箱侧把 acquire/compile/run/cleanup 回传，这里只记一行，避免重复打点
        if (response != null && response.getStageMs() != null) {
            Map<String, Long> stages = response.getStageMs();
            log.info("evt=judge.sandbox.stage submitId={} traceId={} acquireMs={} compileMs={} runMs={} cleanupMs={} totalMs={}",
                    problemSubmit.getId(), traceId == null ? "-" : traceId, stages.get("acquire"),
                    stages.get("compile"), stages.get("run"), stages.get("cleanup"), stages.get("total"));
        }
        if (response == null) {
            // 沙箱客户端异常（连接失败/超时）返回 null：按运行错误落库，不吞掉整条任务
            log.error("evt=judge.sandbox.no_response submitId={} traceId={}", problemSubmit.getId(),
                    traceId == null ? "-" : traceId);
            JudgeInfo noResponse = JudgeInfo.pending(0, "判题服务异常：沙箱无响应");
            noResponse.setStatus(JudgeInfoEnum.RUNTIME_ERROR.getValue());
            return noResponse;
        }

        // 3、将代码沙箱返回的响应转成JudgeInfo (工厂+策略模式消除if-else)
        return resStrategyFactory
                .getStrategy(ExecuteCodeStatusEnum.getEnumByValue(response.getCode()))
                .processExecuteCodeRes(response, inputList, expectedOutput, judgeConfig);
    }
}
