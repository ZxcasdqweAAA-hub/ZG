package com.study.zhiguang.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.zhiguang.counter.schema.CounterKeys;
import com.study.zhiguang.counter.schema.CounterSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;


/**
 * 计数事件聚合与刷写消费者。
 *
 * <p>职责：</p>
 * - 消费点赞/收藏等增量事件，写入 Redis 聚合桶（Hash）；
 * - 以固定延迟定时任务将聚合增量折叠到 SDS 固定结构计数；
 * - 刷写成功后删除聚合字段，避免重复加算。
 */
@Service
@Slf4j
public class CounterAggregationConsumer {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> aggregateScript;
    private final DefaultRedisScript<Long> flushFieldScript;
    private final DefaultRedisScript<Long> cleanupActiveScript;

    // 使用 Redis Hash 作为持久化聚合桶：agg:{schema}:{etype}:{eid} ，field=idx ，value=delta
    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.aggregateScript = new DefaultRedisScript<>();
        this.aggregateScript.setResultType(Long.class);
        this.aggregateScript.setScriptText(AGGREGATE_AND_ACTIVATE_LUA);

        this.flushFieldScript = new DefaultRedisScript<>();
        this.flushFieldScript.setResultType(Long.class);
        this.flushFieldScript.setScriptText(FLUSH_FIELD_LUA);

        this.cleanupActiveScript = new DefaultRedisScript<>();
        this.cleanupActiveScript.setResultType(Long.class);
        this.cleanupActiveScript.setScriptText(CLEANUP_ACTIVE_LUA);
    }

    /**
     * 消费计数事件并写入聚合桶
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg")
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        CounterEvent evt = objectMapper.readValue(message, CounterEvent.class);
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        String activeKey = CounterKeys.activeAggKey();
        String field = String.valueOf(evt.getIdx());

        Long value = redis.execute(
                aggregateScript,
                List.of(aggKey, activeKey),
                field,
                String.valueOf(evt.getDelta())
        );
        if (value == null) {
            throw new IllegalStateException("计数增量写入 Redis 失败");
        }

        // 聚合桶和 Active Set 均已原子落入 Redis 后，再提交 Kafka 位点。
        ack.acknowledge();
    }

    /**
     * 将聚合增量刷写到 SDS 固定结构计数。
     * 固定延迟 1s，保证秒级最终一致性。
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        String activeKey = CounterKeys.activeAggKey();
        Set<String> keys = redis.opsForSet().members(activeKey);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String aggKey : keys) {
            Set<Object> fields = redis.opsForHash().keys(aggKey);
            if (fields == null || fields.isEmpty()) {
                cleanupActiveKey(activeKey, aggKey);
                continue;
            }

            // 解析 etype/eid 以定位 SDS key
            String[] parts = aggKey.split(":", 4); // agg:schema:etype:eid
            if (parts.length < 4) {
                log.warn("忽略格式非法的计数聚合桶: {}", aggKey);
                continue;
            }

            String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);

            for (Object fieldObject : fields) {
                String field = String.valueOf(fieldObject);
                int idx;
                try {
                    idx = Integer.parseInt(field);
                } catch (NumberFormatException nfe) {
                    log.warn("忽略计数聚合桶中的非法字段, aggKey={}, field={}", aggKey, field);
                    continue;
                }
                if (idx < 0 || idx >= CounterSchema.SCHEMA_LEN) {
                    log.warn("忽略越界的计数指标, aggKey={}, idx={}", aggKey, idx);
                    continue;
                }

                try {
                    // 原子完成：读取聚合增量、更新最终计数、删除已处理字段、清理 Active Set。
                    redis.execute(flushFieldScript, List.of(aggKey, cntKey, activeKey),
                            field,
                            String.valueOf(CounterSchema.SCHEMA_LEN),
                            String.valueOf(CounterSchema.FIELD_SIZE));
                } catch (Exception ex) {
                    // Lua 未成功时聚合字段仍保留，下一轮继续重试。
                    log.warn("计数聚合桶刷写失败, aggKey={}, field={}", aggKey, field, ex);
                }
            }

            // 兜底清理由无效/已被其他实例处理而留下的 Active Set 成员。
            cleanupActiveKey(activeKey, aggKey);
        }
    }

    private void cleanupActiveKey(String activeKey, String aggKey) {
        try {
            redis.execute(cleanupActiveScript, List.of(aggKey, activeKey), aggKey);
        } catch (Exception ex) {
            log.warn("清理空聚合桶的 Active Set 索引失败, aggKey={}", aggKey, ex);
        }
    }

    /**
     * 原子维护 Redis Hash 聚合桶与 Active Set。
     * 只有聚合桶从空变为非空时才写 Active Set，避免每个事件都写全局索引。
     */
    private static final String AGGREGATE_AND_ACTIVATE_LUA = """
            local aggKey = KEYS[1]
            local activeKey = KEYS[2]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])

            local wasEmpty = redis.call('HLEN', aggKey) == 0
            local value = redis.call('HINCRBY', aggKey, field, delta)

            if value == 0 then
              redis.call('HDEL', aggKey, field)
            end

            local size = redis.call('HLEN', aggKey)
            if size == 0 then
              redis.call('SREM', activeKey, aggKey)
            elseif wasEmpty then
              redis.call('SADD', activeKey, aggKey)
            end

            return value
            """;

    /**
     * 原子地将聚合桶中的一个字段转移到最终二进制计数，并维护 Active Set。
     */
    private static final String FLUSH_FIELD_LUA = """
            local aggKey = KEYS[1]
            local cntKey = KEYS[2]
            local activeKey = KEYS[3]
            local field = ARGV[1]
            local schemaLen = tonumber(ARGV[2])
            local fieldSize = tonumber(ARGV[3])

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local deltaRaw = redis.call('HGET', aggKey, field)
            if not deltaRaw then
              if redis.call('HLEN', aggKey) == 0 then
                redis.call('SREM', activeKey, aggKey)
              end
              return 0
            end

            local delta = tonumber(deltaRaw)
            local idx = tonumber(field)
            if not delta or not idx or idx < 0 or idx >= schemaLen then
              return 0
            end

            local cnt = redis.call('GET', cntKey)
            local expectedLen = schemaLen * fieldSize
            if not cnt or string.len(cnt) ~= expectedLen then
              cnt = string.rep(string.char(0), expectedLen)
            end

            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            if v > 4294967295 then v = 4294967295 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)

            redis.call('SET', cntKey, cnt)
            redis.call('HDEL', aggKey, field)

            if redis.call('HLEN', aggKey) == 0 then
              redis.call('SREM', activeKey, aggKey)
            end

            return delta
            """;

    /** 仅当聚合桶为空时移除 Active Set 成员，避免误删并发新写入的索引。 */
    private static final String CLEANUP_ACTIVE_LUA = """
            local aggKey = KEYS[1]
            local activeKey = KEYS[2]
            local member = ARGV[1]

            if redis.call('HLEN', aggKey) == 0 then
              return redis.call('SREM', activeKey, member)
            end
            return 0
            """;
}
