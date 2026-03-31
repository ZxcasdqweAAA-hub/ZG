package com.study.zhiguang.knowpost.api.dto;

import java.util.List;
/*
Record 旨在替代传统的只包含数据的简单类（POJO/DTO），
由编译器自动生成构造方法、equals()、hashCode()、toString()
以及各个字段的访问方法，大大减少了样板代码。

Record 的字段默认是 private final，没有 setter，只能通过构造方法赋值，之后不可修改。非常适合作为数据传输对象（DTO）或值对象。

Record 的语义是不可变的数据载体，其所有状态必须通过构造方法明确传入
所以不允许{}内部添加私有字段，当然可以添加静态字段或者实例（（）里面的）方法
 */
public record FeedPageResponse(
    List<FeedItemResponse> items,
    int page,
    int size,
    boolean hasMore
) {}
