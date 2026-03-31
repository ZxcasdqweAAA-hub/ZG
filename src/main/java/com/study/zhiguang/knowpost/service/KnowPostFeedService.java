package com.study.zhiguang.knowpost.service;

import com.study.zhiguang.knowpost.api.dto.FeedPageResponse;

public interface KnowPostFeedService {
    FeedPageResponse getPublicFeed(int page, int size, Long currentUserIdNullable);

    FeedPageResponse getMyPublished(long userId, int page, int size);
}
