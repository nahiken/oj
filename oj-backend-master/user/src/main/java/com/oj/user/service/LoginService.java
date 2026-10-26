package com.oj.user.service;

import javax.servlet.http.HttpServletResponse;

import com.oj.user.model.dto.AccountLoginReq;
import com.oj.user.model.dto.CodeLoginReq;
import com.oj.user.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface LoginService extends IService<User> {
    void sendMailCode(String email);

    void loginByAccount(AccountLoginReq req, HttpServletResponse res);

    void loginByCode(CodeLoginReq req, HttpServletResponse res);
}
