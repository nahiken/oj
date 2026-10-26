package com.oj.user.model.dto;

import javax.validation.constraints.Email;

import lombok.Data;

@Data
public class AccountLoginReq {
    @Email
    private String email;
    private String password;
}