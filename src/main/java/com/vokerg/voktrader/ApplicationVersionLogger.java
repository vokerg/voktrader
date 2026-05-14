package com.vokerg.voktrader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class ApplicationVersionLogger {
    @EventListener(ApplicationReadyEvent.class)
    public void logVersion() {
        Package appPackage = ApplicationVersionLogger.class.getPackage();
        String version = appPackage == null ? null : appPackage.getImplementationVersion();
        log.info("VOKTRADER JAVA VERSION: version={} gitCommit={}", version, gitCommit());
    }

    private String gitCommit() {
        String fromProperty = System.getProperty("git.commit");
        if (hasText(fromProperty)) {
            return fromProperty;
        }
        String fromEnv = System.getenv("GIT_COMMIT");
        if (hasText(fromEnv)) {
            return fromEnv;
        }
        try {
            Path head = Path.of(".git", "HEAD");
            if (!Files.exists(head)) {
                return "unknown";
            }
            String value = Files.readString(head).trim();
            if (!value.startsWith("ref:")) {
                return value;
            }
            Path ref = Path.of(".git", value.substring("ref:".length()).trim());
            return Files.exists(ref) ? Files.readString(ref).trim() : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
