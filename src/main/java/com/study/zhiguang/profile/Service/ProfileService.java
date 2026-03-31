package com.study.zhiguang.profile.Service;

import com.study.zhiguang.profile.api.dto.ProfilePatchRequest;
import com.study.zhiguang.profile.api.dto.ProfileResponse;
import com.study.zhiguang.user.domain.User;

import java.util.Optional;
/*
个人资料相关接口
 */
public interface ProfileService {
    Optional<User> getById(long id);

    ProfileResponse updateProfile(long userId, ProfilePatchRequest req);

    ProfileResponse updateAvatar(long userId, String avatarUrl);
}
