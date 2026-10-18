package com.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor for background ingestion.
 *
 * <p>Bounded on purpose: ingestion is embedding-API bound, and letting an
 * unbounded pool run means a batch of uploads hits the provider's rate limit all
 * at once. The queue is bounded too, with CALLER_RUNS as the rejection policy —
 * an over-full queue then slows the upload endpoint down instead of silently
 * dropping a document that the frontend is already polling for.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("ingestExecutor")
    public TaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight ingestion finish on shutdown rather than leaving documents
        // stuck at status="processing" forever.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Executor for SSE chat streams.
     *
     * <p>Separate from ingestion so a queue of uploads cannot delay a chat request
     * — the old {@code Executors.newCachedThreadPool()} inside the controller was
     * unbounded and never shut down, so a burst of requests could spawn threads
     * without limit.
     */
    @Bean("chatExecutor")
    public TaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chat-sse-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
