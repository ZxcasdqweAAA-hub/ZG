package com.study.zhiguang.user.service;

import com.study.zhiguang.user.domain.User;

import java.util.Optional;
/*
optional
表达“可能不存在”
强制处理null情况
函数式变成（链式处理流）

 */
public interface UserService {

    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findById(long id);

    boolean existsByPhone(String phone);
    boolean existsByEmail(String email);

    User createUser(User user);
    void updatePassword(User user);
}
