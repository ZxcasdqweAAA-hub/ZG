package com.study.zhiguang.counter.schema;

public class UserCounterKeys {
    private UserCounterKeys() {}

    public static String sdsKey(long userId) {
        return "ucnt:" + userId; // 用户维度固定结构计数（SDS）键
    }
}
