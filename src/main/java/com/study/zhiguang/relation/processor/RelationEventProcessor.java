package com.study.zhiguang.relation.processor;

import com.study.zhiguang.counter.service.UserCounterService;
import com.study.zhiguang.relation.event.RelationEvent;
import com.study.zhiguang.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RelationEventProcessor {
    private static final Duration RELATION_CACHE_TTL = Duration.ofHours(2);
    private static final int FANS_CACHE_LIMIT = 500;

    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    public RelationEventProcessor(RelationMapper mapper, StringRedisTemplate redis, UserCounterService userCounterService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    public void process(RelationEvent evt) {
        String dk = "dedup:rel:" + evt.type() + ":" + evt.fromUserId() + ":" + evt.toUserId() + ":" + (evt.id() == null ? "0" : String.valueOf(evt.id()));
        Boolean first = redis.opsForValue().setIfAbsent(dk, "1", Duration.ofMinutes(10));

        if (first == null || !first) {
            return;
        }
        if ("FollowCreated".equals(evt.type())) {
            mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);
            long now = System.currentTimeMillis();

            updateFollowingCacheOnCreate(evt, now);
            updateFansCacheOnCreate(evt, now);

            userCounterService.incrementFollowings(evt.fromUserId(), 1);
            userCounterService.incrementFollowers(evt.toUserId(), 1);
        } else if ("FollowCanceled".equals(evt.type())) {
            mapper.cancelFollower(evt.toUserId(), evt.fromUserId());

            updateFollowingCacheOnCancel(evt);
            updateFansCacheOnCancel(evt);

            userCounterService.incrementFollowings(evt.fromUserId(), -1);
            userCounterService.incrementFollowers(evt.toUserId(), -1);
        }
    }

    private void updateFollowingCacheOnCreate(RelationEvent evt, long score) {
        String key = followingKey(evt.fromUserId());
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        redis.opsForZSet().add(key, String.valueOf(evt.toUserId()), score);
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private void updateFansCacheOnCreate(RelationEvent evt, long score) {
        String key = fansKey(evt.toUserId());
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        redis.opsForZSet().add(key, String.valueOf(evt.fromUserId()), score);
        trimFansCache(key);
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private void updateFollowingCacheOnCancel(RelationEvent evt) {
        String key = followingKey(evt.fromUserId());
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        redis.opsForZSet().remove(key, String.valueOf(evt.toUserId()));
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private void updateFansCacheOnCancel(RelationEvent evt) {
        String key = fansKey(evt.toUserId());
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        redis.opsForZSet().remove(key, String.valueOf(evt.fromUserId()));
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private void trimFansCache(String key) {
        Long size = redis.opsForZSet().zCard(key);
        if (size != null && size > FANS_CACHE_LIMIT) {
            redis.opsForZSet().removeRange(key, 0, size - FANS_CACHE_LIMIT - 1);
        }
    }

    private String followingKey(long userId) {
        return "uf:flws:" + userId;
    }

    private String fansKey(long userId) {
        return "uf:fans:" + userId;
    }
}
