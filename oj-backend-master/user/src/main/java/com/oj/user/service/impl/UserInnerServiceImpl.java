package com.oj.user.service.impl;

import org.apache.dubbo.config.annotation.DubboService;

import com.oj.user.api.dto.SecretDTO;
import com.oj.user.api.service.UserInnerService;
import com.oj.user.mapper.UserMapper;
import com.oj.user.model.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

@DubboService
public class UserInnerServiceImpl extends ServiceImpl<UserMapper, User> implements UserInnerService {
    @Override
    public SecretDTO getSecretByUid(Long uid) {
        User user = baseMapper.selectOne(new LambdaQueryWrapper<User>().select(User::getSecretId, User::getSecretKey).eq(User::getUid, uid));
        SecretDTO secretDTO = new SecretDTO();
        secretDTO.setSecretId(user.getSecretId());
        secretDTO.setSecretKey(user.getSecretKey());
        return secretDTO;
    }
}
