package com.jlas.e2e.support;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Test evidence only. Callers must use fixed descriptions, never credentials or tokens. */
public final class SeleniumStepLog {
    private final Path directory;
    private int sequence;

    public SeleniumStepLog(String caseId) throws IOException {
        directory = Path.of("target", "selenium-runs",
                Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID(),
                caseId.replaceAll("[^A-Za-z0-9_-]", "_"));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("steps.tsv"),
                "timestamp_utc\tstep\taction\texpected\tstatus\tduration_ms\tobserved\n");
    }

    public void step(WebDriver driver, String action, String expected, Runnable operation) {
        int number = ++sequence;
        long started = System.nanoTime();
        record(number, action, expected, "START", 0, "");
        try {
            operation.run();
            record(number, action, expected, "PASS", elapsed(started), "Assertion completed");
        } catch (RuntimeException | AssertionError failure) {
            record(number, action, expected, "FAIL", elapsed(started), failure.getClass().getSimpleName());
            if (driver instanceof TakesScreenshot screenshot) {
                try {
                    Files.write(directory.resolve("step-" + number + "-failure.png"),
                            screenshot.getScreenshotAs(OutputType.BYTES));
                } catch (Exception captureFailure) {
                    record(number, "Screenshot", "Failure evidence", "ERROR", 0,
                            captureFailure.getClass().getSimpleName());
                }
            }
            throw failure;
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private void record(int number, String action, String expected, String status, long millis, String observed) {
        String line = String.join("\t", Instant.now().toString(), Integer.toString(number),
                clean(action), clean(expected), status, Long.toString(millis), clean(observed)) + "\n";
        try {
            Files.writeString(directory.resolve("steps.tsv"), line, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to write Selenium step evidence", failure);
        }
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
