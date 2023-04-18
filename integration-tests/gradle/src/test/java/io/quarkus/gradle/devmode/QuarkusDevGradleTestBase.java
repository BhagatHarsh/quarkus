package io.quarkus.gradle.devmode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import io.quarkus.gradle.BuildResult;
import io.quarkus.gradle.QuarkusGradleWrapperTestBase;
import io.quarkus.test.devmode.util.DevModeTestUtils;

public abstract class QuarkusDevGradleTestBase extends QuarkusGradleWrapperTestBase {

    private Future<?> quarkusDev;
    protected File projectDir;

    @Override
    protected void setupTestCommand() {
        gradleNoWatchFs(false);
    }

    @Test
    public void main() throws Exception {

        projectDir = getProjectDir();
        beforeQuarkusDev();
        ExecutorService executor = null;
        AtomicReference<BuildResult> buildResult = new AtomicReference<>();
        try {
            executor = Executors.newSingleThreadExecutor();
            quarkusDev = executor.submit(() -> {
                try {
                    buildResult.set(build());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to build the project", e);
                }
            });
            testDevMode();
        } catch (Exception | AssertionError e) {
            if (buildResult.get() != null) {
                System.err.println("BELOW IS THE CAPTURED LOGGING OF THE FAILED GRADLE TEST PROJECT BUILD");
                System.err.println(buildResult.get().getOutput());
            } else {
                File logOutput = new File(projectDir, "command-output.log");
                if (logOutput.exists()) {
                    System.err.println("BELOW IS THE CAPTURED LOGGING OF THE FAILED GRADLE TEST PROJECT BUILD");
                    try (BufferedReader reader = Files.newBufferedReader(logOutput.toPath())) {
                        String line = reader.readLine();
                        while (line != null) {
                            System.err.println(line);
                            line = reader.readLine();
                        }
                    }
                } else {
                    System.err.println("GRADLE TEST PROJECT BUILD OUTPUT IS NOT AVAILABLE");
                }
            }
            throw e;
        } finally {
            if (quarkusDev != null) {
                quarkusDev.cancel(true);
            }
            if (executor != null) {
                executor.shutdownNow();
            }

            // Kill all processes that were (indirectly) spawned by the current process.
            DevModeTestUtils.killDescendingProcesses();

            DevModeTestUtils.awaitUntilServerDown();

            if (projectDir != null && projectDir.isDirectory()) {
                FileUtils.deleteQuietly(projectDir);
            }
        }
    }

    protected BuildResult build() throws Exception {
        return runGradleWrapper(projectDir, buildArguments());
    }

    protected abstract String projectDirectoryName();

    protected File getProjectDir() {
        if (projectDir == null) {
            final String projectDirName = projectDirectoryName();
            try {
                final File projectDir = Files.createTempDirectory(projectDirName).toFile();
                FileUtils.copyDirectory(getProjectDir(projectDirName), projectDir);
                this.projectDir = projectDir;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create a project dir for " + projectDirName, e);
            }
        }
        return projectDir;
    }

    protected String[] buildArguments() {
        return new String[] { "clean", "quarkusDev" };
    }

    protected void beforeQuarkusDev() throws Exception {
    }

    protected abstract void testDevMode() throws Exception;

    protected String getHttpResponse() {
        return DevModeTestUtils.getHttpResponse(getQuarkusDevBrokenReason());
    }

    protected String getHttpResponse(String path) {
        return getHttpResponse(path, devModeTimeoutSeconds(), TimeUnit.SECONDS);
    }

    protected String getHttpResponse(String path, long timeout, TimeUnit tu) {
        return DevModeTestUtils.getHttpResponse(path, false, getQuarkusDevBrokenReason(), timeout, tu);
    }

    private Supplier<String> getQuarkusDevBrokenReason() {
        return () -> {
            return quarkusDev == null ? null : quarkusDev.isDone() ? "quarkusDev mode has terminated" : null;
        };
    }

    protected void replace(String srcFile, Map<String, String> tokens) {
        final File source = new File(getProjectDir(), srcFile);
        assertThat(source).exists();
        try {
            DevModeTestUtils.filter(source, tokens);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to replace tokens in " + source, e);
        }
    }

    protected void assertUpdatedResponseContains(String path, String value) {
        assertUpdatedResponseContains(path, value, devModeTimeoutSeconds(), TimeUnit.SECONDS);
    }

    protected int devModeTimeoutSeconds() {
        // It's a wild guess, but maybe Windows is just slower - at least: a successful Gradle-CI-jobs on Windows is
        // 2.5x slower than the same Gradle-CI-job on Linux.
        if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) {
            return 90;
        }
        return 60;
    }

    protected void assertUpdatedResponseContains(String path, String value, long waitAtMost, TimeUnit timeUnit) {
        await()
                .pollDelay(100, TimeUnit.MILLISECONDS)
                .atMost(waitAtMost, timeUnit).until(() -> getHttpResponse(path, waitAtMost, timeUnit).contains(value));
    }
}
