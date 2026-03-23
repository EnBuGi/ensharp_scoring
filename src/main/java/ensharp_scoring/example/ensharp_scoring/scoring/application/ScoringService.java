package ensharp_scoring.example.ensharp_scoring.scoring.application;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

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
            fetchSourceCodePort.fetch(request.getRepoUrl(), workspaceDir, request.getGithubAccessToken());
            
            // [New] 프로젝트 구조 유연화: src 폴더 위치 조정 (테스트 주입 전 수행)
            adjustProjectStructure(workspaceDir);

            // 3. 테스트 케이스 다운로드 및 압축 해제
            if (request.getTestCodeUrl() == null || request.getTestCodeUrl().isBlank()) {
                log.error("[ScoringService] Test case URL is missing for submission: {}", submissionId);
                throw new ScoringException("Test case URL is missing for submission: " + submissionId);
            }

            // [New] 기존 테스트 디렉토리 제거 (구조 조정 후 수행하여 이동된 학생 테스트도 제거)
            Path existingTestDir = workspaceDir.resolve("src/test");
            if (Files.exists(existingTestDir)) {
                log.info("[ScoringService] Removing existing test directory for isolation: {}", existingTestDir);
                org.springframework.util.FileSystemUtils.deleteRecursively(existingTestDir);
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
        
        // 2. gradle.properties 생성 (네이티브 서비스 비활성화, 메모리 최적화, 오프라인 모드용 캐시 사용)
        Path gradlePropertiesPath = workspaceDir.resolve("gradle.properties");
        if (!Files.exists(gradlePropertiesPath)) {
            String propertiesContent = "org.gradle.native=false\n" +
                                     "org.gradle.vfs.watch=false\n" +
                                     "org.gradle.daemon=false\n" +
                                     "gradle.user.home=/home/gradle/.gradle\n" +
                                     "org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=128m\n" +
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

    private void adjustProjectStructure(Path workspaceDir) {
        try {
            // 1. src 디렉토리 탐색
            Path srcDir = findSrcDirectory(workspaceDir);
            
            // 2. src가 존재하지 않는 경우 (예: 루트에 바로 .java 파일이 있는 경우)
            if (srcDir == null) {
                log.info("[ScoringService] No src directory found. Checking for .java files in root or subdirectories.");
                // 루트에 소스파일이 있는 것으로 보이면 src/main/java를 생성하여 이동 시도
                ensureStandardStructure(workspaceDir);
                return;
            }

            // 3. src가 루트에 있지 않은 경우 (예: Subfolder/src)
            if (!srcDir.getParent().equals(workspaceDir)) {
                log.info("[ScoringService] Found src directory at non-root location: {}. Moving to root.", workspaceDir.relativize(srcDir));
                Path sourceRoot = srcDir.getParent();
                moveContentsToRoot(sourceRoot, workspaceDir);
            }
            
            // 4. src/main/java 구조가 아닌 경우 (예: src/com/...)
            Path mainJavaDir = workspaceDir.resolve("src/main/java");
            if (!Files.exists(mainJavaDir)) {
                log.info("[ScoringService] src/main/java missing. Attempting to fix standard structure.");
                // src 아래에 바로 패키지나 파일이 있는 경우를 대비
                fixSrcStructure(workspaceDir.resolve("src"));
            }
            
        } catch (Exception e) {
            log.error("[ScoringService] Error adjusting project structure", e);
        }
    }

    private void ensureStandardStructure(Path workspaceDir) throws IOException {
        Path targetDir = workspaceDir.resolve("src/main/java");
        Files.createDirectories(targetDir);
        
        // .java 파일이 포함된 디렉토리를 찾아 targetDir로 이동
        try (java.util.stream.Stream<Path> stream = Files.walk(workspaceDir)) {
            List<Path> javaFolders = stream
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::getParent)
                .distinct()
                .filter(p -> !p.toString().contains("src/test")) // 테스트는 제외 (나중에 덮어씌움)
                .sorted((p1, p2) -> p1.toString().length() - p2.toString().length()) // 최상위 폴더 우선
                .collect(java.util.stream.Collectors.toList());
            
            if (!javaFolders.isEmpty()) {
                Path sourceBase = javaFolders.get(0);
                log.info("[ScoringService] Moving code from {} to standard structure.", workspaceDir.relativize(sourceBase));
                moveContentsTo(sourceBase, targetDir);
            }
        }
    }

    private void fixSrcStructure(Path srcDir) throws IOException {
        // src/com/... -> src/main/java/com/...
        Path mainJavaDir = srcDir.resolve("main/java");
        
        try (java.util.stream.Stream<Path> stream = Files.list(srcDir)) {
            List<Path> items = stream.collect(java.util.stream.Collectors.toList());
            for (Path item : items) {
                String name = item.getFileName().toString();
                if (name.equals("main") || name.equals("test")) continue;
                
                Files.createDirectories(mainJavaDir);
                Path target = mainJavaDir.resolve(name);
                if (!Files.exists(target)) {
                    Files.move(item, target);
                }
            }
        }
    }

    private void moveContentsToRoot(Path sourceRoot, Path workspaceDir) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(sourceRoot)) {
            stream.forEach(p -> {
                try {
                    Path target = workspaceDir.resolve(sourceRoot.relativize(p));
                    if (!Files.exists(target)) {
                        Files.move(p, target);
                    }
                } catch (IOException e) {
                    log.warn("Failed to move {} to root: {}", p, e.getMessage());
                }
            });
        }
    }

    private void moveContentsTo(Path source, Path targetDir) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(source)) {
            stream.forEach(p -> {
                try {
                    Path target = targetDir.resolve(source.relativize(p));
                    if (!Files.exists(target)) {
                        Files.move(p, target);
                    }
                } catch (IOException e) {
                    log.warn("Failed to move {} to {}: {}", p, targetDir, e.getMessage());
                }
            });
        }
    }

    private Path findSrcDirectory(Path startDir) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(startDir, 5)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("src"))
                    .findFirst()
                    .orElse(null);
        }
    }

}

