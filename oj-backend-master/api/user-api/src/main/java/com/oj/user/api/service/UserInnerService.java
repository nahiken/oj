package com.oj.user.api.service;

import com.oj.user.api.dto.SecretDTO;

public interface UserInnerService {
    SecretDTO getSecretByUid(Long uid);
}
