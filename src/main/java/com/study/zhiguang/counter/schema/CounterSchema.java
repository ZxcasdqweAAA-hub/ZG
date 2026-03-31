package com.study.zhiguang.counter.schema;

import java.util.Map;
import java.util.Set;

/*
计数 Schema 映射及常量定义
阶段1采用 8 字节 Int64 固定偏移 SDS 后续可平滑替换为 5 字节实现
 */
public final class CounterSchema {

    /*
    使用v1 schema 下标约定
    0 read
    1 like
    2 fav
    3 comment
    4 repost

     */
    public static final String SCHEMA_ID = "v1";
    public static final int FIELD_SIZE = 4;
    public static final int SCHEMA_LEN = 5;

    public static final int IDX_LIKE = 1;
    public static final int IDX_FAV = 2;

    public static final Map<String, Integer> NAME_TO_IDX = Map.of(
            "like", IDX_LIKE,
            "fav", IDX_FAV
    );

    public static final Set<String> SUPPORTED_METRICS = NAME_TO_IDX.keySet();

    private CounterSchema() {}
}
