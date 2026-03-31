package com.study.zhiguang.relation.api;

import com.study.zhiguang.auth.token.JwtService;
import com.study.zhiguang.counter.service.UserCounterService;
import com.study.zhiguang.profile.api.dto.ProfileResponse;
import com.study.zhiguang.relation.mapper.RelationMapper;
import com.study.zhiguang.relation.service.RelationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 关系接口控制器。
 * 职责：关注/取消关注、关系三态查询、关注/粉丝列表（偏移与游标）、用户维度计数读取与采样自检。
 * 缓存：ZSet 存储关注/粉丝列表；用户计数采用 SDS 固定结构（5×4 字节，大端编码），提供采样一致性校验与按需重建。
 */
@RestController
@RequestMapping("/api/v1/relation")
@RequiredArgsConstructor
public class RelationContorller {
    private final RelationService relationService;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;
    private final RelationMapper relationMapper;

    /**
     * 发起关注。
     * @param toUserId 被关注的用户ID
     * @param jwt 认证令牌
     * @return 是否关注成功
     */
    @PostMapping("/follow")
    public boolean follow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long fromUserId = jwtService.extractUserId(jwt);
        return relationService.follow(fromUserId, toUserId);
    }

    /**
     * 取消关注。
     * @param toUserId 被取消关注的用户ID
     * @param jwt 认证令牌
     * @return 是否取消成功
     */
    @PostMapping("/unfollow")
    public boolean unfollow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.unfollow(uid, toUserId);
    }

    /**
     * 查询与目标用户的关系三态。
     * @param toUserId 目标用户ID
     * @param jwt 认证令牌
     * @return following/followedBy/mutual 三态
     */
    @GetMapping("/status")
    public Map<String, Boolean> status(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.relationStatus(uid, toUserId);
    }

    /**
     * 获取关注列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（当 cursor 为空时生效）
     * @param cursor 游标（毫秒时间戳）
     * @return 关注用户ID列表
     */
    @GetMapping("/following")
    public List<ProfileResponse> following(@RequestParam("userId") long userId,
                                           @RequestParam(value = "limit", defaultValue = "20") int limit,
                                           @RequestParam(value = "offset", defaultValue = "0") int offset,
                                           @RequestParam(value = "cursor", required = false) Long cursor) {
        int l = Math.min(Math.max(limit, 1), 100);
        return relationService.followingProfiles(userId, l, Math.max(offset, 0), cursor);
    }

    /**
     * 获取粉丝列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（当 cursor 为空时生效）
     * @param cursor 游标（毫秒时间戳）
     * @return 粉丝用户ID列表
     */
    @GetMapping("/followers")
    public List<ProfileResponse> followers(@RequestParam("userId") long userId,
                                           @RequestParam(value = "limit", defaultValue = "20") int limit,
                                           @RequestParam(value = "offset", defaultValue = "0") int offset,
                                           @RequestParam(value = "cursor", required = false) Long cursor) {
        int l = Math.min(Math.max(limit, 1), 100);
        return relationService.followersProfiles(userId, l, Math.max(offset, 0), cursor);
    }

    /**
     * 获取用户维度计数（SDS）。
     * 结构与一致性：SDS 由 5 个 4 字节段组成（关注/粉丝/发文/获赞/获藏），按需触发采样校验与重建，保证接口稳定可用。
     * @param userId 用户ID
     * @return 各计数指标的值
     */
    //todo


}
