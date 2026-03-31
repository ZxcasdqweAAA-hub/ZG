package com.study.zhiguang.knowpost.model;


import lombok.Data;

import java.time.Instant;

@Data
public class KnowPostFeedRow {
    private Long id;
    private String title;
    private String description;
    private String tags; //JSON字符串
    private String imgUrls;    // JSON 字符串
    private String authorAvatar;
    private String authorNickname;
    private String authorTagJson; // 作者的领域标签 JSON
    private Instant publishTime;
    private Boolean isTop;
}