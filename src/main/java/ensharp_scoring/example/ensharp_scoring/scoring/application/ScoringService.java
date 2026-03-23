package ensharp_scoring.example.ensharp_scoring.scoring.application;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.IOException;
import java.io.File;

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
            
            // [New] 권한 설정: 구조 조정 및 테스트 주입 전 모든 파일에 대해 권한 확보
            setWorkspacePermissions(workspaceDir);

            // [New] 프로젝트 구조 유연화: src 폴더 위치 조정
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

        // 3. build.gradle 생성 (항상 덮어쓰기 하여 시스템 템플릿 강제 적용)
        String templatePath = "SPRING".equalsIgnoreCase(projectType) 
                ? "templates/spring-build.gradle" 
                : "templates/java-build.gradle";

        try (var inputStream = new org.springframework.core.io.ClassPathResource(templatePath).getInputStream()) {
            String template = org.springframework.util.StreamUtils.copyToString(inputStream, java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(buildGradlePath, template);
            log.info("[ScoringService] Forcefully generated build.gradle for project type: {} using template: {}", projectType, templatePath);
        } catch (Exception e) {
            log.error("Failed to read build.gradle template: {}", templatePath, e);
            throw new ScoringException("Failed to generate build.gradle from template: " + templatePath, e);
        }
    }

    private void adjustProjectStructure(Path workspaceDir) {
        try {
            log.info("[ScoringService] Starting robust project structure adjustment for workspace: {}", workspaceDir);
            Path targetMainJava = workspaceDir.resolve("src/main/java");
            Files.createDirectories(targetMainJava);

            // 1. 모든 .java 파일 찾기 (src/test 내부 파일은 제외)
            List<Path> javaFiles;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                javaFiles = stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".java"))
                    .filter(p -> !p.toString().contains("src/test"))
                    .collect(Collectors.toList());
            }

            log.info("[ScoringService] Found {} .java files for structure adjustment", javaFiles.size());
            for (Path f : javaFiles) {
                log.info("[ScoringService]   - Discovered: {}", workspaceDir.relativize(f));
            }

            if (javaFiles.isEmpty()) {
                log.info("[ScoringService] No .java files found in workspace.");
                return;
            }

            // 2. 각 파일의 패키지 분석 후 이동
            for (Path sourceFile : javaFiles) {
                String packageName = parsePackageName(sourceFile);
                Path packagePath = packageName.isEmpty() ? Paths.get("") : Paths.get(packageName.replace(".", "/"));
                Path targetPath = targetMainJava.resolve(packagePath).resolve(sourceFile.getFileName());

                // 이미 올바른 위치에 있는 경우 건너뜀
                if (sourceFile.toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                log.info("[ScoringService] Moving student code: {} -> {}", workspaceDir.relativize(sourceFile), workspaceDir.relativize(targetPath));
                
                // 이동 시 기존 파일이 있으면 덮어씀 (Standard 구조로 정규화)
                Files.move(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 3. 빈 디렉토리 정리 (src 제외)
            cleanUpEmptyDirectories(workspaceDir);

            log.info("[ScoringService] Completed robust project structure adjustment.");
        } catch (Exception e) {
            log.error("[ScoringService] Error during robust project structure adjustment", e);
        }
    }

    private void cleanUpEmptyDirectories(Path workspaceDir) {
        try {
            // 하위 디렉토리부터 삭제하기 위해 depth 순으로 정렬하거나 walk 후 뒤집어서 처리
            List<Path> directories;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                directories = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(workspaceDir))
                    .filter(p -> !p.toString().contains("src")) // src 폴더 계열은 보존
                    .sorted((p1, p2) -> p2.toString().length() - p1.toString().length())
                    .collect(Collectors.toList());
            }

            for (Path dir : directories) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (s.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete empty directory: {}", dir);
                }
            }
        } catch (IOException e) {
            log.warn("Error during cleanup of empty directories", e);
        }
    }

    private void setWorkspacePermissions(Path workspaceDir) {
        log.info("[ScoringService] Setting 777 permissions for workspace: {}", workspaceDir);
        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            stream.forEach(p -> {
                try {
                    File file = p.toFile();
                    file.setWritable(true, false);
                    file.setReadable(true, false);
                    file.setExecutable(true, false);
                } catch (Exception e) {
                    log.warn("[ScoringService] Failed to set permissions for: {}", p);
                }
            });
        } catch (IOException e) {
            log.error("[ScoringService] Error while setting workspace permissions", e);
        }
    }

    private String parsePackageName(Path javaFile) {
        // 성능 최적화: 첫 100라인만 읽음
        try (Stream<String> lines = Files.lines(javaFile).limit(100)) {
            return lines
                .map(String::trim)
                .filter(line -> line.startsWith("package ") && line.endsWith(";"))
                .map(line -> {
                    String part = line.substring(8, line.length() - 1).trim();
                    String pkg = part.split("\\s+")[0];
                    // 보안: 패키지명에 영문, 숫자, 점만 허용 (Path Traversal 방지)
                    if (pkg.matches("^[a-zA-Z0-9.]+$")) {
                        return pkg;
                    }
                    log.warn("[ScoringService] Invalid package name found: {}", pkg);
                    return "";
                })
                .findFirst()
                .orElse(""); // default package
        } catch (IOException e) {
            log.warn("[ScoringService] Failed to read file for package parsing: {}", javaFile);
            return "";
        }
    }

}

