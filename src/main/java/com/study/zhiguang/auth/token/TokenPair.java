package com.study.zhiguang.auth.token;


import java.time.Instant;

/*
这是访问令牌和刷新令牌的组合
accessToken 访问令牌 JWT字符串，Bearer使用
accessTokenExpiresAt 访问令牌过期时间
refreshToken 刷新令牌 JWT字符串，仅用于刷新接口
accessTokenExpiresAt ~
refreshTokenId 刷新令牌ID
 */
public record TokenPair(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String refreshTokenId
) {}
