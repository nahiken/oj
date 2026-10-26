package com.oj.judge.memory.controller;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oj.common.auth.annotation.TokenCheck;
import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.dto.R;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.judge.memory.dto.AiTutoringSessionReq;
import com.oj.judge.memory.session.AiTutoringSessionService;

/**
 * 辅导会话回调（P7 票 01）。
 *
 * <p>**不经网关**：`ai` 直连 judge，网关还会把 `/api/judge/learning/ai/**`
 * 一律 404。但只挡住网关不够——judge 监听在 8012，绕过网关直连同样能打到这里，而这张表
 * 是 `二次 AC 率` 的分母。所以再加一道共享密钥：请求必须带 `X-Internal-Key`，与
 * `oj.ai.internal-key` 相等；未配置密钥时**一律拒绝**（fail closed），
 * 宁可没有数字，也不要一个可以自己写的数字。
 *
 * <p>Cookie 仍然要校验：uid 由 token 决定，请求体里没有 uid。
 *
 * <p>返回 `false` 表示这条记录被丢弃（数据不合法或库不可用），调用方不需要重试：
 * 统计少一行可以接受，阻塞用户的辅导流不可以。
 */
@RestController
@RequestMapping("/learning/ai")
public class AiSessionController {

    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    @Resource
    private AiTutoringSessionService sessionService;

    @Value("${oj.ai.internal-key:}")
    private String internalKey;

    @PostMapping("/session")
    @TokenCheck
    public R<Boolean> record(@RequestHeader(value = INTERNAL_KEY_HEADER, required = false) String providedKey,
            @RequestBody AiTutoringSessionReq req) {
        ThrowUtils.throwIf(!StringUtils.hasText(internalKey) || !internalKey.equals(providedKey),
                HttpCodeEnum.NO_AUTH, "内部接口");
        Long uid = TokenUtils.getCurrentUid();
        if (uid == null) {
            return R.ok(false);
        }
        return R.ok(sessionService.record(uid, req));
    }
}
