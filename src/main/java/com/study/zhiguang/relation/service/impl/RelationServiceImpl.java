package com.study.zhiguang.relation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.zhiguang.profile.api.dto.ProfileResponse;
import com.study.zhiguang.relation.event.RelationEvent;
import com.study.zhiguang.relation.mapper.RelationMapper;
import com.study.zhiguang.relation.outbox.OutboxMapper;
import com.study.zhiguang.relation.service.RelationService;
import com.study.zhiguang.user.domain.User;
import com.study.zhiguang.user.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RelationServiceImpl implements RelationService {
    private static final Duration RELATION_CACHE_TTL = Duration.ofHours(2);
    private static final int FANS_CACHE_LIMIT = 500;

    private final RelationMapper mapper;
    private final OutboxMapper outboxMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenScript;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;

    public RelationServiceImpl(RelationMapper mapper,
                               OutboxMapper outboxMapper,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               UserMapper userMapper) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public boolean follow(long fromUserId, long toUserId) {
        Long ok = redis.execute(tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
        if (ok == 0L) {
            return false;
        }

        long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        int inserted = mapper.insertFollowing(id, fromUserId, toUserId, 1);

        if (inserted > 0) {
            try {
                Long outId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
                String payload = objectMapper.writeValueAsString(new RelationEvent("FollowCreated", fromUserId, toUserId, id));
                outboxMapper.insert(outId, "following", id, "FollowCreated", payload);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public boolean unfollow(long fromUserId, long toUserId) {
        int updated = mapper.cancelFollowing(fromUserId, toUserId);
        if (updated > 0) {
            try {
                Long outId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
                String payload = objectMapper.writeValueAsString(new RelationEvent("FollowCanceled", fromUserId, toUserId, null));
                outboxMapper.insert(outId, "following", null, "FollowCanceled", payload);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isFollowing(long fromUserId, long toUserId) {
        return mapper.existsFollowing(fromUserId, toUserId) > 0;
    }

    @Override
    public List<Long> following(long userId, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        String key = followingKey(userId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            // 关注缓存是全量缓存，返回不足 limit 代表已经到达列表末尾。
            return zsetOffset(key, offset, limit);
        }

        // 缓存不存在时，当前请求直接由数据库回答；全量数据只用于构建关注缓存。
        List<Long> result = mapper.listFollowing(userId, limit, offset);
        rebuildFullFollowingCache(userId, key);
        return result;
    }

    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        String key = fansKey(userId);
        boolean exists = Boolean.TRUE.equals(redis.hasKey(key));

        if (!exists) {
            // 当前页必须直接由数据库回答，因为本次请求可能已经超出 Top 500。
            List<Long> result = mapper.listFollowers(userId, limit, offset);
            rebuildTopFansCache(userId, key);
            return result;
        }

        if (!withinFansCache(offset, limit)) {
            return mapper.listFollowers(userId, limit, offset);
        }

        Long cachedSize = redis.opsForZSet().zCard(key);
        long requiredSize = (long) offset + limit;
        if (cachedSize == null || cachedSize < requiredSize) {
            // Top 500 可能因取关出现空洞；已有缓存不在这里补位，等待 TTL 后重建。
            return mapper.listFollowers(userId, limit, offset);
        }

        return zsetOffset(key, offset, limit);
    }

    @Override
    public Map<String, Boolean> relationStatus(long userId, long otherUserId) {
        boolean following = isFollowing(userId, otherUserId);
        boolean followedBy = isFollowing(otherUserId, userId);
        boolean mutual = following && followedBy;
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("following", following);
        m.put("followedBy", followedBy);
        m.put("mutual", mutual);
        return m;
    }

    @Override
    public List<Long> followingCursor(long userId, int limit, Long cursor) {
        if (limit <= 0) {
            return List.of();
        }
        String key = followingKey(userId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            // 关注缓存是全量缓存，游标查询不足 limit 代表已经到达末尾。
            return zsetCursor(key, limit, cursor);
        }

        List<Long> result = listFollowingFromDatabaseByCursor(userId, limit, cursor);
        rebuildFullFollowingCache(userId, key);
        return result;
    }

    @Override
    public List<Long> followersCursor(long userId, int limit, Long cursor) {
        if (limit <= 0) {
            return List.of();
        }
        String key = fansKey(userId);
        boolean exists = Boolean.TRUE.equals(redis.hasKey(key));

        if (!exists) {
            // 数据库回答当前游标请求，Top 500 仅用于建立后续请求的读缓存。
            List<Long> result = listFollowersFromDatabaseByCursor(userId, limit, cursor);
            rebuildTopFansCache(userId, key);
            return result;
        }

        List<Long> cached = zsetCursor(key, limit, cursor);
        if (cached.size() >= limit) {
            return cached;
        }

        // 返回不足可能是真正到尾部，也可能是 Top 500 边界或取关造成的空洞。
        // 当前没有完整性元数据，因此回源数据库，且不修改已经存在的缓存。
        return listFollowersFromDatabaseByCursor(userId, limit, cursor);
    }

    @Override
    public List<ProfileResponse> followingProfiles(long userId, int limit, int offset, Long cursor) {
        List<Long> ids = cursor != null ? followingCursor(userId, limit, cursor) : following(userId, limit, offset);
        return toProfiles(ids);
    }

    @Override
    public List<ProfileResponse> followersProfiles(long userId, int limit, int offset, Long cursor) {
        List<Long> ids = cursor != null ? followersCursor(userId, limit, cursor) : followers(userId, limit, offset);
        return toProfiles(ids);
    }

    private void rebuildFullFollowingCache(long userId, String key) {
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        Map<Long, Map<String, Object>> rows = mapper.listAllFollowingRows(userId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        fillZSet(key, rows, "toUserId", "createdAt", null);
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private void rebuildTopFansCache(long userId, String key) {
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        Map<Long, Map<String, Object>> rows = mapper.listTopFollowerRows(userId, FANS_CACHE_LIMIT);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        fillZSet(key, rows, "fromUserId", "createdAt", null);
        redis.expire(key, RELATION_CACHE_TTL);
    }

    private List<Long> listFollowingFromDatabaseByCursor(long userId, int limit, Long cursor) {
        if (cursor == null) {
            return mapper.listFollowing(userId, limit, 0);
        }
        Map<Long, Map<String, Object>> rows = mapper.listFollowingRowsBeforeCursor(userId, cursor, limit);
        return rowIdsOrderedByScore(rows, "toUserId", "createdAt");
    }

    private List<Long> listFollowersFromDatabaseByCursor(long userId, int limit, Long cursor) {
        if (cursor == null) {
            return mapper.listFollowers(userId, limit, 0);
        }
        return mapper.listFollowersBeforeCursor(userId, cursor, limit);
    }

    private boolean withinFansCache(int offset, int limit) {
        return offset >= 0 && (long) offset + limit <= FANS_CACHE_LIMIT;
    }

    private List<Long> zsetOffset(String key, int offset, int limit) {
        Set<String> set = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
        return set == null ? Collections.emptyList() : toLongList(set);
    }

    private List<Long> zsetCursor(String key, int limit, Long cursor) {
        double max = cursor == null ? Double.POSITIVE_INFINITY : cursor.doubleValue() - 1D;
        Set<String> set = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);
        return set == null ? Collections.emptyList() : toLongList(set);
    }

    private void fillZSet(String key,
                          Map<Long, Map<String, Object>> rows,
                          String idField,
                          String tsField,
                          Long cursor) {
        for (Map<String, Object> r : rows.values()) {
            Object idObj = r.get(idField);
            Object tsObj = r.get(tsField);
            if (idObj == null || tsObj == null) {
                continue;
            }
            long score = tsScore(tsObj);
            if (cursor == null || score < cursor) {
                redis.opsForZSet().add(key, String.valueOf(idObj), score);
            }
        }
    }

    private long tsScore(Object tsObj) {
        if (tsObj instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (tsObj instanceof Date d) {
            return d.getTime();
        }
        return System.currentTimeMillis();
    }

    private List<Long> rowIdsOrderedByScore(Map<Long, Map<String, Object>> rows,
                                            String idField,
                                            String tsField) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.values().stream()
                .filter(row -> row.get(idField) != null && row.get(tsField) != null)
                .sorted((left, right) -> Long.compare(tsScore(right.get(tsField)), tsScore(left.get(tsField))))
                .map(row -> Long.valueOf(String.valueOf(row.get(idField))))
                .toList();
    }

    private List<Long> toLongList(Set<String> set) {
        List<Long> out = new ArrayList<>(set.size());
        for (String s : set) {
            out.add(Long.valueOf(s));
        }
        return out;
    }

    private List<ProfileResponse> toProfiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<User> users = userMapper.listByIds(ids);
        Map<Long, User> m = new LinkedHashMap<>(users.size());
        for (User u : users) {
            m.put(u.getId(), u);
        }
        List<ProfileResponse> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = m.get(id);
            if (u == null) {
                continue;
            }
            out.add(new ProfileResponse(u.getId(), u.getNickname(), u.getAvatar(), u.getBio(), u.getZgId(), u.getGender(), u.getBirthday(), u.getSchool(), u.getPhone(), u.getEmail(), u.getTagsJson()));
        }
        return out;
    }

    private String followingKey(long userId) {
        return "uf:flws:" + userId;
    }

    private String fansKey(long userId) {
        return "uf:fans:" + userId;
    }

    private static final String TOKEN_BUCKET_LUA = """
            
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = redis.call('TIME')[1]
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            if not last then last = now; tokens = capacity end
            local elapsed = tonumber(now) - tonumber(last)
            local add = elapsed * rate
            tokens = math.min(capacity, tonumber(tokens) + add)
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            tokens = tokens - 1
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            redis.call('PEXPIRE', key, 60000)
            return 1
            """;
}
