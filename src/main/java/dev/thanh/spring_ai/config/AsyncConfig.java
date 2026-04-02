package dev.thanh.spring_ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AsyncConfig implements WebMvcConfigurer {

    private final Executor virtualThreadExecutor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        AsyncTaskExecutor taskExecutor = new TaskExecutorAdapter(virtualThreadExecutor);
        configurer.setTaskExecutor(taskExecutor);
//        configurer.setDefaultTimeout(30000); // 30 seconds
    }
}