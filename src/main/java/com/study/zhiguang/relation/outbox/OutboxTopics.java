package com.study.zhiguang.relation.outbox;


/**
 * Outbox 相关 Kafka 主题常量。
 * 约定：Canal 将 outbox 表的行变更转发至主题 `canal-outbox`，下游消费者据此处理。
 */
public class OutboxTopics {

    private OutboxTopics(){} //不允许初始化
    public static final String CANAL_OUTBOX = "canal-outbox";

}
