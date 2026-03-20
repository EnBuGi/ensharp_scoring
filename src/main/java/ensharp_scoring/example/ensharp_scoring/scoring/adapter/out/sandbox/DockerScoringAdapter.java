package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
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
        // 프로세스 단의 타임아웃 계산 (문제의 timeLimit + 버퍼 시간)
        long timeoutMs = request.getTimeLimit() + 3000;

        File resultsDir = new File("/tmp/results/" + request.getSubmissionId());

        try {
            // Create results directory so Docker can mount it without creating it as a root-owned directory

            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }

            List<String> command = buildDockerCommand(request);
            log.info("[DockerScoringAdapter] Executing Docker command: {}", String.join(" ", command));
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Capture process output in a separate thread to avoid blocking
            java.util.concurrent.CompletableFuture<String> outputFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[DockerOutput] {}", line);
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                } catch (IOException e) {
                    log.error("[DockerOutput] Error reading process output", e);
                    return "";
                }
            });

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                log.warn("[DockerScoring] Process timed out for submission: {}", request.getSubmissionId());
                return buildFallbackResult(request.getSubmissionId(), ScoringStatus.TIME_LIMIT_EXCEEDED);
            }

            int exitCode = process.exitValue();
            log.info("[DockerScoring] Process exited with code: {} for submission: {}", exitCode, request.getSubmissionId());
            
            // OOM 에러 확인 (도커 exit code 137)
            if (exitCode == 137) {
                return buildFallbackResult(request.getSubmissionId(), ScoringStatus.MEMORY_LIMIT_EXCEEDED);
            }

            // 테스트 결과가 저장될 XML 파일
            File resultXmlFile = new File("/tmp/results/" + request.getSubmissionId() + "/test-results.xml");
            
            if (!resultXmlFile.exists()) {
                if (exitCode != 0) {
                    return buildFallbackResult(request.getSubmissionId(), ScoringStatus.COMPILE_ERROR);
                } else {
                    return buildFallbackResult(request.getSubmissionId(), ScoringStatus.RUNTIME_ERROR);
                }
            }

            // XML 파싱을 통한 구체적 성공 / 실패 판별 로직 호출
            return resultParser.parse(request.getSubmissionId(), resultXmlFile, exitCode);
            
        } catch (InterruptedException e) {
            log.error("[DockerScoringAdapter] Interrupted during scoring", e);
            Thread.currentThread().interrupt();
            throw new ScoringException("Scoring process was interrupted", e);
        } catch (Exception e) {
            log.error("[DockerScoringAdapter] Critical error during execution for submission: {}", request.getSubmissionId(), e);
            return buildFallbackResult(request.getSubmissionId(), ScoringStatus.RUNTIME_ERROR);
        } finally {
            try {
                org.springframework.util.FileSystemUtils.deleteRecursively(resultsDir);
            } catch (Exception ignored) {
                // Ignore cleanup errors
            }
        }
    }

    private List<String> buildDockerCommand(ScoringRequest request) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        
        // 동적 자원 제한 (메시지로 전달된 memoryLimit 사용)
        command.add("--memory=" + request.getMemoryLimit() + "m");
        
        // 도커 보안 정책 강화 설정
        command.add("--network=none"); // 네트워크 단절
        command.add("--pids-limit=64"); // 포크 폭탄 방지
        command.add("--cap-drop=ALL"); // 루트 권한 등 불필요 권한 회수
        
        // 필요한 볼륨만 마운트
        command.add("-v"); 
        command.add("/tmp/workspace/" + request.getSubmissionId() + ":/workspace");
        command.add("-v"); 
        command.add("/tmp/results/" + request.getSubmissionId() + ":/results");
        
        // 작업 디렉토리 설정
        command.add("-w");
        command.add("/workspace");
        
        // 프로젝트 타입별로 최적화된(의존성이 캐싱된) 베이스 이미지 사용
        String baseImage = "huri0906/enbug-grading-java-base-image:latest";
        if ("SPRING".equalsIgnoreCase(request.getProjectType())) {
            baseImage = "huri0906/enbug-grading-spring-base-image:latest";
        }
        command.add(baseImage);
        
        command.add("gradle");
        command.add("test");
        // Gradle 홈 디렉토리를 볼륨 내로 설정하여 Read-Only 환경에서도 캐시 쓰기 가능하게 함
        command.add("-Dgradle.user.home=/workspace/.gradle");
        
        return command;
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
