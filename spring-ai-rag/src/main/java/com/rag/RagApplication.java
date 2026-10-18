package com.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code @EnableAsync} lives on {@link com.rag.config.AsyncConfig} together with
 * the two bounded executors it enables, not here.
 */
@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
        System.out.println("服务启动完毕，再次测试是否成功");
    }
}
