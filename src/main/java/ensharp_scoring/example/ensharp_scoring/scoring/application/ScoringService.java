package ensharp_scoring.example.ensharp_scoring.scoring.application;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.in.ScoreSubmissionUseCase;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchSourceCodePort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchTestCasePort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService implements ScoreSubmissionUseCase {

    private final FetchSourceCodePort fetchSourceCodePort;
    private final FetchTestCasePort fetchTestCasePort;
    private final ExecuteScoringPort executeScoringPort;
    private final PublishScoringResultPort publishScoringResultPort;

    @Override
    public void score(ScoringRequest request) {
        String submissionId = request.getSubmissionId();
        Path workspaceDir = Paths.get("/tmp/workspace", submissionId);
        
        try {
            // 1. 작업 디렉토리 생성
            Files.createDirectories(workspaceDir);
            
            // 2. 깃허브 코드 클론
            fetchSourceCodePort.fetch(request.getRepoUrl(), workspaceDir);
            
            // 3. 테스트 케이스 다운로드 및 압축 해제
            if (request.getTestCodeUrl() == null || request.getTestCodeUrl().isBlank()) {
                log.error("[ScoringService] Test case URL is missing for submission: {}", submissionId);
                throw new ScoringException("Test case URL is missing for submission: " + submissionId);
            }
            log.info("[ScoringService] Fetching test cases from URL: {}", request.getTestCodeUrl());
            fetchTestCasePort.fetch(request.getTestCodeUrl(), workspaceDir);
            log.info("[ScoringService] Successfully fetched test cases");
            
            // 4. Gradle 빌드 파일 생성 (멀티 템플릿 지원)
            generateGradleFiles(workspaceDir, request.getProjectType());
            
            // [Debug] 워크스페이스 구조 확인 및 권한 설정
            log.info("[ScoringService] Setting 777 permissions and logging workspace for {}:", submissionId);
            try (java.util.stream.Stream<Path> paths = Files.walk(workspaceDir)) {
                paths.forEach(p -> {
                    try {
                        p.toFile().setWritable(true, false);
                        p.toFile().setReadable(true, false);
                        p.toFile().setExecutable(true, false);
                        log.info("  - {} (Perms set)", workspaceDir.relativize(p));
                    } catch (Exception e) {
                        log.warn("Failed to set permissions for: {}", p);
                    }
                });
            }
            
            // 5. 채점(도커) 실행
            ScoringResult result = executeScoringPort.execute(request);
            
            // 6. 채점 결과 발행
            publishScoringResultPort.publish(result);
            
        } catch (ScoringException e) {

            log.error("Scoring process failed for submission: {}", submissionId, e);
            ScoringResult errorResult = ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(ScoringStatus.RUNTIME_ERROR)
                .totalTests(0)
                .passedTests(0)
                .details(Collections.emptyList())
                .build();
            publishScoringResultPort.publish(errorResult);
        } catch (Exception e) {
            log.error("Unexpected error during scoring for submission: {}", submissionId, e);
            ScoringResult errorResult = ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(ScoringStatus.EXECUTION_ERROR)
                .totalTests(0)
                .passedTests(0)
                .details(Collections.emptyList())
                .build();
            publishScoringResultPort.publish(errorResult);
        } finally {
            try {
                org.springframework.util.FileSystemUtils.deleteRecursively(workspaceDir);
            } catch (Exception ignored) {
                log.warn("Failed to delete workspace directory: {}", workspaceDir);
            }
        }
    }

    private void generateGradleFiles(Path workspaceDir, String projectType) throws Exception {
        Path buildGradlePath = workspaceDir.resolve("build.gradle");
        Path settingsGradlePath = workspaceDir.resolve("settings.gradle");

        // 1. settings.gradle 생성 (Gradle 8.7+ 필수)
        if (!Files.exists(settingsGradlePath)) {
            Files.writeString(settingsGradlePath, "rootProject.name = 'submission'\n");
            log.info("Generated settings.gradle in workspace.");
        }
        
        // 2. gradle.properties 생성 (네이티브 서비스 비활성화 및 메모리 최적화)
        Path gradlePropertiesPath = workspaceDir.resolve("gradle.properties");
        if (!Files.exists(gradlePropertiesPath)) {
            String propertiesContent = "org.gradle.native=false\n" +
                                     "org.gradle.vfs.watch=false\n" +
                                     "org.gradle.daemon=false\n" +
                                     "gradle.user.home=/tmp/.gradle\n" +
                                     "org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=96m\n" +
                                     "org.gradle.welcome=never\n";
            Files.writeString(gradlePropertiesPath, propertiesContent);
            log.info("Generated gradle.properties in workspace.");
        }

        // 3. build.gradle 생성
        if (Files.exists(buildGradlePath)) {
            log.info("build.gradle already exists in workspace, skipping generation.");
            return;
        }

        String templatePath = "SPRING".equalsIgnoreCase(projectType) 
                ? "templates/spring-build.gradle" 
                : "templates/java-build.gradle";

        try (var inputStream = new org.springframework.core.io.ClassPathResource(templatePath).getInputStream()) {
            String template = org.springframework.util.StreamUtils.copyToString(inputStream, java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(buildGradlePath, template);
            log.info("Generated build.gradle for project type: {} using template: {}", projectType, templatePath);
        } catch (Exception e) {
            log.error("Failed to read build.gradle template: {}", templatePath, e);
            throw new ScoringException("Failed to generate build.gradle from template: " + templatePath, e);
        }
    }

}

