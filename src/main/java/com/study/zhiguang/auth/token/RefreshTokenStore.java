package com.study.zhiguang.auth.token;

import java.time.Duration;

/*
刷新令牌白名单存储接口
负责管理Refresh Token 的有效性
存储撤销校验单个令牌于撤销用户所有令牌

 */
public interface RefreshTokenStore {

    /*
    userId 用户id
    tokenId 令牌jti
    ttl     生存时间
     */
    void storeToken(long userId, String tokenId, Duration ttl);

    /**
     * 校验刷新令牌是否仍然有效（在白名单内且未过期）。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID（jti）。
     * @return 是否有效。
     */
    boolean isTokenValid(long userId, String tokenId);

    /**
     * 撤销指定刷新令牌（从白名单移除）。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID（jti）。
     */
    void revokeToken(long userId, String tokenId);

    /**
     * 撤销用户的所有刷新令牌（强制该用户所有会话下线）。
     *
     * @param userId 用户 ID。
     */
    void revokeAll(long userId);
}
