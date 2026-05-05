package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerScoringAdapter implements ExecuteScoringPort {

    private final JUnitXmlResultParser resultParser;

    @Override
    public ScoringResult execute(ScoringRequest request) {
        String submissionId = request.getSubmissionId();
        String containerName = "sandbox-" + submissionId;
        long timeoutMs = request.getTimeLimit() + 10000; // Increased buffer for safety
        
        File resultsDir = new File("/tmp/results/" + submissionId);
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        try {
            // 1. 컨테이너 생성 (실행은 하지 않음)
            log.info("[DockerScoring] Step 1: Creating container {}", containerName);
            runCommand(buildCreateCommand(request, containerName));

            // 2. 워크스페이스 파일 복사
            log.info("[DockerScoring] Step 2: Copying workspace to container");
            String workspacePath = "/tmp/workspace/" + submissionId + "/.";
            runCommand(List.of("docker", "cp", workspacePath, containerName + ":/home/gradle/app/"));

            // 3. 실행 및 채점 (로그 캡처)
            log.info("[DockerScoring] Step 3: Running grading task");
            DockerExecutionResult runResult = runCaptureOutput(List.of("docker", "start", "-a", containerName), timeoutMs);
            log.info("[DockerScoring] Grading process finished with exit code: {}", runResult.exitCode);

            // 4. 결과 파일 추출
            log.info("[DockerScoring] Step 4: Extracting results from container");
            try {
                runCommand(List.of("docker", "cp", containerName + ":/home/gradle/app/build/test-results/test/.", resultsDir.getAbsolutePath()));
            } catch (Exception e) {
                log.warn("[DockerScoring] Failed to extract XML results: {}", e.getMessage());
            }

            // OOM 또는 타임아웃 처리
            if (runResult.exitCode == 137) {
                return buildFallbackResult(submissionId, ScoringStatus.MEMORY_LIMIT_EXCEEDED, runResult.output);
            }
            if (runResult.timedOut) {
                return buildFallbackResult(submissionId, ScoringStatus.TIME_LIMIT_EXCEEDED, runResult.output);
            }

            // 5. 결과 파싱
            log.info("[DockerScoring] Step 5: Parsing results from XML");
            File[] xmlFiles = resultsDir.listFiles((dir, name) -> name.endsWith(".xml"));
            List<File> resultXmlFiles = new ArrayList<>();
            if (xmlFiles != null) {
                Collections.addAll(resultXmlFiles, xmlFiles);
            }

            if (resultXmlFiles.isEmpty()) {
                log.warn("[DockerScoring] No XML results found. ExitCode={}", runResult.exitCode);
                // If exit code is non-zero and no XML exists, it's likely a COMPILE_ERROR or a crash before tests.
                ScoringStatus status = (runResult.exitCode != 0) ? ScoringStatus.COMPILE_ERROR : ScoringStatus.RUNTIME_ERROR;
                if (runResult.output.contains("Compilation failed")) {
                    status = ScoringStatus.COMPILE_ERROR;
                }
                return buildFallbackResult(submissionId, status, runResult.output);
            }

            return resultParser.parse(submissionId, resultXmlFiles, runResult.exitCode, request.getTestCases(), runResult.output);

        } catch (Exception e) {
            log.error("[DockerScoring] Critical error for submission: {}", submissionId, e);
            return buildFallbackResult(submissionId, ScoringStatus.EXECUTION_ERROR, e.getMessage());
        } finally {
            // 6. 클린업
            log.info("[DockerScoring] Step 6: Cleaning up container and local results");
            try {
                runCommand(List.of("docker", "rm", "-f", containerName));
                org.springframework.util.FileSystemUtils.deleteRecursively(resultsDir);
            } catch (Exception ignored) {}
        }
    }

    private List<String> buildCreateCommand(ScoringRequest request, String containerName) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("create");
        command.add("--name");
        command.add(containerName);
        command.add("--memory=" + (request.getMemoryLimit() + 256) + "m");
        command.add("--network=none"); // Security: No network access
        command.add("--pids-limit=64"); // Security: Prevent fork bombs
        command.add("--cap-drop=ALL"); // Security: Drop all capabilities
        command.add("--read-only");   // Security: Read-only root filesystem
        
        // Mount writable volumes for Gradle/Java requirements
        command.add("--tmpfs"); command.add("/tmp");
        command.add("--tmpfs"); command.add("/home/gradle/.gradle");
        command.add("--tmpfs"); command.add("/home/gradle/app/build");
        
        // Custom cache mount (must be writable even if image is pre-warmed)
        command.add("--volume");
        command.add("gradle-cache:/gradle-user-home-cache:rw");

        command.add("-w");
        command.add("/home/gradle/app");
        command.add("--user");
        command.add("gradle"); // Run as non-root user
        
        String baseImage = request.getProjectType().equalsIgnoreCase("SPRING")
                ? "huri0906/enbug-grading-spring-base-image:v4"
                : "huri0906/enbug-grading-java-base-image:v4";
        command.add(baseImage);
        
        command.add("sh");
        command.add("-c");
        command.add("export GRADLE_USER_HOME=/gradle-user-home-cache && " +
                   "gradle test --no-daemon " +
                   "-PtestMaxHeapSize=" + request.getMemoryLimit() + "m " +
                   "-Dorg.gradle.jvmargs=\"-Xmx128m\" " +
                   "-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.daemon=false -Dorg.gradle.welcome=never");
        
        return command;
    }

    private void runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            String errorMsg = String.format("[DockerCommand] Command failed or timed out (exit=%d): %s",
                    finished ? process.exitValue() : -1, String.join(" ", command));
            log.warn(errorMsg);
            throw new IOException(errorMsg);
        }
    }

    private DockerExecutionResult runCaptureOutput(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        int MAX_LINES = 5000;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineCount < MAX_LINES) {
                    log.info("[DockerOutput] {}", line);
                    sb.append(line).append("\n");
                    lineCount++;
                } else if (lineCount == MAX_LINES) {
                    String limitMsg = "\n[SYSTEM] Log limit exceeded (5000 lines). Truncating output for stability.";
                    log.warn(limitMsg);
                    sb.append(limitMsg).append("\n");
                    lineCount++; // ensure it only appends once
                }
            }
        }

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new DockerExecutionResult(137, true, sb.toString());
        }
        return new DockerExecutionResult(process.exitValue(), false, sb.toString());
    }

    private static class DockerExecutionResult {
        int exitCode;
        boolean timedOut;
        String output;
        DockerExecutionResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
        }
    }

    private ScoringResult buildFallbackResult(String submissionId, ScoringStatus status, String log) {
        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(status)
                .totalTests(0)
                .passedTests(0)
                .details(Collections.emptyList())
                .buildLog(log != null ? log : "")
                .build();
    }
}
