package com.tjc.bugagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Starts the bug agent platform.
 */
@EnableAsync
@SpringBootApplication
public class BugAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BugAgentApplication.class, args);
    }
}

