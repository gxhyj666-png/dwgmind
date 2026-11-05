package com.dwgmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 图语者 DwgMind 启动类
 * 工业图纸语义引擎：把图纸从「图形」变成「可检索、可计算、可复用的资产」
 */
@EnableAsync
@SpringBootApplication
public class DwgMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DwgMindApplication.class, args);
    }
}
