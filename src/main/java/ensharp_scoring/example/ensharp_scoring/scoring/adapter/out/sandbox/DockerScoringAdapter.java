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
        long timeoutMs = request.getTimeLimit() + 5000;
        
        File resultsDir = new File("/tmp/results/" + submissionId);
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        try {
            // 1. 컨테이너 생성 (실행은 하지 않음)
            log.info("[DockerScoring] Step 1: Creating container {}", containerName);
            runCommand(buildCreateCommand(request, containerName));

            // 2. 워크스페이스 파일 복사 (Docker socket을 통해 직접 전송)
            log.info("[DockerScoring] Step 2: Copying workspace to container");
            String workspacePath = "/tmp/workspace/" + submissionId + "/.";
            runCommand(List.of("docker", "cp", workspacePath, containerName + ":/home/gradle/app/"));

            // 3. 실행 및 채점 (로그 캡처)
            // --user root 로 실행하므로 별도의 chown 과정 없이 바로 실행 가능
            log.info("[DockerScoring] Step 3: Running grading task (as root)");
            DockerExecutionResult runResult = runCaptureOutput(List.of("docker", "start", "-a", containerName), timeoutMs);
            log.info("[DockerScoring] Grading process finished with exit code: {}", runResult.exitCode);

            // 4. 결과 파일 추출
            log.info("[DockerScoring] Step 4: Extracting results from container");
            try {
                runCommand(List.of("docker", "cp", containerName + ":/home/gradle/app/build/test-results/test/.", resultsDir.getAbsolutePath()));
            } catch (Exception e) {
                log.warn("[DockerScoring] Failed to extract XML results. Maybe tests failed to run? {}", e.getMessage());
            }

            // OOM 또는 타임아웃 처리
            if (runResult.exitCode == 137) {
                return buildFallbackResult(submissionId, ScoringStatus.MEMORY_LIMIT_EXCEEDED);
            }
            if (runResult.timedOut) {
                return buildFallbackResult(submissionId, ScoringStatus.TIME_LIMIT_EXCEEDED);
            }

            // 5. 결과 파싱 (모든 XML 파일 수집)
            log.info("[DockerScoring] Step 5: Parsing results from XML");
            File[] xmlFiles = resultsDir.listFiles((dir, name) -> name.endsWith(".xml"));
            List<File> resultXmlFiles = new ArrayList<>();
            if (xmlFiles != null) {
                for (File file : xmlFiles) {
                    resultXmlFiles.add(file);
                }
            }

            if (resultXmlFiles.isEmpty()) {
                log.warn("[DockerScoring] No XML results found for submission: {}", submissionId);
                return buildFallbackResult(submissionId, runResult.exitCode == 0 ? ScoringStatus.RUNTIME_ERROR : ScoringStatus.COMPILE_ERROR);
            }

            return resultParser.parse(submissionId, resultXmlFiles, runResult.exitCode, request.getTestCases());

        } catch (Exception e) {
            log.error("[DockerScoring] Critical error for submission: {}", submissionId, e);
            return buildFallbackResult(submissionId, ScoringStatus.RUNTIME_ERROR);
        } finally {
            // 6. 클린업 (컨테이너 삭제)
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
        command.add("--network=none");
        command.add("--pids-limit=1024");
        command.add("-w");
        command.add("/home/gradle/app");
        
        // root 사용자로 실행하여 권한 문제 해결
        command.add("--user");
        command.add("root");
        
        String baseImage = request.getProjectType().equalsIgnoreCase("SPRING_BOOT")
                ? "huri0906/enbug-grading-spring-base-image:v3"
                : "huri0906/enbug-grading-java-base-image:v3";
        command.add(baseImage);
        
        // 실행 명령 (start -a 시 실행됨)
        // ls -la 로 파일 존재 여부 확인
        // chmod -R 777로 캐시 디렉토리 권한 부여 (root가 lock 파일을 생성할 수 있게 함)
        // --offline 플래그로 네트워크 없이 캐시 사용 강제
        // -Dorg.gradle.jvmargs="-Xmx128m" 로 데몬 프로세스 힙 제한 강제 (GRADLE_OPTS보다 우선순위 높음)
        command.add("sh");
        command.add("-c");
        command.add("echo '--- Runtime Environment ---' && " +
                   "id && echo \"GRADLE_USER_HOME: /gradle-user-home-cache\" && " +
                   "echo '--- Workspace Structure ---' && " +
                   "find . -maxdepth 5 -not -path '*/.*' && " +
                   "echo '--- Cache Size (/gradle-user-home-cache) ---' && " +
                   "du -sh /gradle-user-home-cache || true && " +
                   "chmod -R 777 /gradle-user-home-cache || true && " +
                   "export GRADLE_USER_HOME=/gradle-user-home-cache && " +
                   "gradle test --no-daemon --offline " +
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
            if (!finished) process.destroyForcibly();
            log.warn("[DockerCommand] Command failed or timed out: {}", String.join(" ", command));
        }
    }

    private DockerExecutionResult runCaptureOutput(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[DockerOutput] {}", line);
                sb.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new DockerExecutionResult(137, true);
        }
        return new DockerExecutionResult(process.exitValue(), false);
    }

    private static class DockerExecutionResult {
        int exitCode;
        boolean timedOut;
        DockerExecutionResult(int exitCode, boolean timedOut) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }
    }

    private ScoringResult buildFallbackResult(String submissionId, ScoringStatus status) {
        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(status)
                .totalTests(0)
                .passedTests(0)
                .details(Collections.emptyList())
                .build();
    }
}
