package com.oj.user.api.dto;

import java.io.Serializable;

import lombok.Data;

@Data
public class SecretDTO implements Serializable{
    private String secretId;
    private String secretKey;
}
