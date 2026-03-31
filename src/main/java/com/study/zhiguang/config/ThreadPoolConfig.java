package com.study.zhiguang.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("NoteExecutor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        //任务队列已满且线程数已达到最大线程数时，再有新任务提交，由调用者线程直接执行方法
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 关闭时等待任务完成：当应用关闭（如 Spring 容器销毁）时，是否等待已提交的任务执行完成后再销毁线程池。
        executor.setAwaitTerminationSeconds(60);
        //等待终止的最大时间：当设置 waitForTasksToCompleteOnShutdown 为 true 后，指定等待任务完成的最大时长（单位：秒）。超过该时间，即使还有任务未完成，也会强制销毁线程池。
        executor.initialize();
        return executor;
    }
}
