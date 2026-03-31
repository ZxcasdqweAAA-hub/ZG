package com.study.zhiguang.relation.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.zhiguang.common.util.OutboxMessageUtil;
import com.study.zhiguang.relation.event.RelationEvent;
import com.study.zhiguang.relation.processor.RelationEventProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CanalOutboxConsumer {
    /**
     * @param objectMapper JSON 序列化器
     * @param processor 关系事件处理器
     */
    private final ObjectMapper objectMapper;
    private final RelationEventProcessor processor;

    /**
     * 消费 Canal outbox 消息并转为关系事件处理。
     * 监听 Canal→Kafka 桥接写入的 outbox 主题；使用手动位点提交
     * @param message Kafka 消息内容
     * @param ack 位点确认对象
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "relation-outbox-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }
            for (JsonNode row : rows) {
                JsonNode payload = row.get("payload");
                if (payload == null) {
                    continue;
                }
                RelationEvent event = objectMapper.readValue(payload.asText(), RelationEvent.class);
                processor.process(event);
            }

        } catch (Exception ignored) {}
    }


}
