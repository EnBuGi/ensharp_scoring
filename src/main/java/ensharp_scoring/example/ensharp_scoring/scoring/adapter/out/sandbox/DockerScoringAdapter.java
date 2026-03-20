package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
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

            // 2. 워크스페이스 파일 복사 (Docker CP 사용 - 볼륨 마운트 이슈 해결)
            log.info("[DockerScoring] Step 2: Copying workspace to container");
            String workspacePath = "/tmp/workspace/" + submissionId + "/.";
            runCommand(List.of("docker", "cp", workspacePath, containerName + ":/home/gradle/"));

            // 3. 권한 설정 (복사된 파일의 소유권을 gradle 유저로 변경)
            log.info("[DockerScoring] Step 3: Setting permissions in container");
            runCommand(List.of("docker", "start", containerName)); // 임시 시작
            runCommand(List.of("docker", "exec", "-u", "root", containerName, "chown", "-R", "gradle:gradle", "/home/gradle"));
            runCommand(List.of("docker", "stop", containerName));

            // 4. 컨테이너 실행 및 채점 (로그 캡처)
            log.info("[DockerScoring] Step 4: Running grading task");
            DockerExecutionResult runResult = runCaptureOutput(List.of("docker", "start", "-a", containerName), timeoutMs);
            log.info("[DockerScoring] Grading process finished with exit code: {}", runResult.exitCode);

            // 5. 결과 파일 추출
            log.info("[DockerScoring] Step 5: Extracting results from container");
            // XML 파일 경로 (Gradle 기본 경로: build/test-results/test/TEST-*.xml)
            // 템플릿의 소스 구조를 고려할 때 build/test-results/test/test-results.xml 로 생성되도록 유도 (혹은 패턴 매칭)
            // 여기서는 build.gradle 설정에 의해 생성됨
            try {
                runCommand(List.of("docker", "cp", containerName + ":/home/gradle/build/test-results/test/.", resultsDir.getAbsolutePath()));
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

            // 6. 결과 파싱
            File resultXmlFile = new File(resultsDir, "test-results.xml");
            if (!resultXmlFile.exists()) {
                // 특정 파일이 없으면 디렉토리 내의 첫 번째 XML 파일을 찾아봄
                File[] xmlFiles = resultsDir.listFiles((dir, name) -> name.endsWith(".xml"));
                if (xmlFiles != null && xmlFiles.length > 0) {
                    resultXmlFile = xmlFiles[0];
                }
            }

            if (!resultXmlFile.exists()) {
                log.warn("[DockerScoring] No XML results found for submission: {}", submissionId);
                return buildFallbackResult(submissionId, runResult.exitCode == 0 ? ScoringStatus.RUNTIME_ERROR : ScoringStatus.COMPILE_ERROR);
            }

            return resultParser.parse(submissionId, resultXmlFile, runResult.exitCode);

        } catch (Exception e) {
            log.error("[DockerScoring] Critical error for submission: {}", submissionId, e);
            return buildFallbackResult(submissionId, ScoringStatus.RUNTIME_ERROR);
        } finally {
            // 7. 클린업
            log.info("[DockerScoring] Step 7: Cleaning up container and local results");
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
        command.add("--memory=" + request.getMemoryLimit() + "m");
        command.add("--network=none");
        command.add("--pids-limit=64");
        command.add("--cap-drop=ALL");
        command.add("-w");
        command.add("/home/gradle");
        
        String baseImage = "huri0906/enbug-grading-java-base-image:latest";
        if ("SPRING".equalsIgnoreCase(request.getProjectType())) {
            baseImage = "huri0906/enbug-grading-spring-base-image:latest";
        }
        command.add(baseImage);
        
        // 실행 명령 (start -a 시 실행됨)
        // --no-daemon 필수로 추가 (네이티브 라이브러리 충돌 최소화)
        command.add("gradle");
        command.add("test");
        command.add("--no-daemon");
        command.add("-Dorg.gradle.native=false");
        command.add("-Dorg.gradle.vfs.watch=false");
        
        return command;
    }

    private void runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
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
