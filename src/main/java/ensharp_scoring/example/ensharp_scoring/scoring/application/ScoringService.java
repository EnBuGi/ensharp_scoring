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

    private static final String TARGET_PACKAGE = "gs.submission";

    @Override
    public void score(ScoringRequest request) {
        String submissionId = request.getSubmissionId();
        Path workspaceDir = Paths.get("/tmp/workspace", submissionId);
        
        try {
            // 1. 작업 디렉토리 생성
            Files.createDirectories(workspaceDir);
            
            // 2. 깃허브 코드 클론
            fetchSourceCodePort.fetch(request.getRepoUrl(), workspaceDir, request.getGithubAccessToken());
            
            // [New] 권한 설정
            setWorkspacePermissions(workspaceDir);

            // 3. 학생 코드 구조 조정 및 패키지 정규화
            log.info("[ScoringService] Normalizing student source code package to {}", TARGET_PACKAGE);
            adjustProjectStructure(workspaceDir);

            // 4. 테스트 케이스 다운로드 및 압축 해제
            if (request.getTestCodeUrl() == null || request.getTestCodeUrl().isBlank()) {
                log.error("[ScoringService] Test case URL is missing for submission: {}", submissionId);
                throw new ScoringException("Test case URL is missing for submission: " + submissionId);
            }

            log.info("[ScoringService] Cleaning up workspace (src/test, build, bin) before fetching tests");
            clearOldArtifacts(workspaceDir);

            log.info("[ScoringService] Fetching test cases from URL: {}", request.getTestCodeUrl());
            fetchTestCasePort.fetch(request.getTestCodeUrl(), workspaceDir);
            
            // [New] 테스트 코드 페치 후 권한 재설정
            setWorkspacePermissions(workspaceDir);

            // [New] 테스트 코드도 패키지 정규화 수행 (학생 코드와 동일한 패키지로 통합)
            log.info("[ScoringService] Normalizing test code package to {}", TARGET_PACKAGE);
            normalizePackage(workspaceDir, "src/test/java");
            
            log.info("[ScoringService] Successfully fetched and normalized test cases");
            
            // 5. Gradle 빌드 파일 생성
            generateGradleFiles(workspaceDir, request.getProjectType());
            
            // [New] 최종 권한 확인 (Docker 실행 직전)
            setWorkspacePermissions(workspaceDir);

            // 6. 채점(도커) 실행
            ScoringResult result = executeScoringPort.execute(request);
            
            // 7. 채점 결과 발행
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
        
        // 2. gradle.properties 생성
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
            log.info("[ScoringService] Starting flexible project structure adjustment for student code");
            Path targetMainJava = workspaceDir.resolve("src/main/java");
            Path targetMainResources = workspaceDir.resolve("src/main/resources");
            Files.createDirectories(targetMainJava);
            Files.createDirectories(targetMainResources);

            // 1. 모든 소스 및 리소스 파일 찾기
            List<Path> allFiles;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("src/test")) // 테스트 코드는 별도로 처리
                    .collect(Collectors.toList());
            }

            // 2. .java 파일 정규화 및 이동
            normalizePackage(workspaceDir, "src/main/java");

            // 3. 리소스 파일 이동
            for (Path f : allFiles) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".xml") || name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")) {
                    Path targetPath = targetMainResources.resolve(f.getFileName().toString());
                    if (!f.equals(targetPath)) {
                        Files.createDirectories(targetPath.getParent());
                        Files.move(f, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            
            cleanUpEmptyDirectories(workspaceDir);
            log.info("[ScoringService] Completed student code structure adjustment.");
        } catch (Exception e) {
            log.error("[ScoringService] Error during project structure adjustment", e);
        }
    }

    private void normalizePackage(Path workspaceDir, String baseDirRelPath) {
        try {
            // 1. 전체 워크스페이스에서 모든 클래스 이름 수집 (import 제거용)
            java.util.Set<String> projectClassNames;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                projectClassNames = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("._")) // Skip macOS metadata
                    .filter(p -> !p.toString().contains("__MACOSX")) // Skip macOS metadata folder
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(java.util.stream.Collectors.toSet());
            }

            // 2. 현재 대상 디렉토리(baseDirRelPath)에 해당하는 Java 파일 탐색
            List<Path> targetJavaFiles;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                targetJavaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("._")) // Skip macOS metadata
                    .filter(p -> !p.toString().contains("__MACOSX")) // Skip macOS metadata folder
                    .filter(p -> {
                        String path = p.toString();
                        // baseDirRelPath가 포함되어 있거나, src 하위가 아닌 루트에 있는 파일들 포함
                        return path.contains(baseDirRelPath) || (!path.contains("src/main") && !path.contains("src/test"));
                    })
                    .collect(Collectors.toList());
            }

            if (targetJavaFiles.isEmpty()) {
                log.info("[ScoringService] No Java files found to normalize in {}", baseDirRelPath);
                return;
            }

            log.info("[ScoringService] Normalizing {} Java files in {} to package {}", targetJavaFiles.size(), baseDirRelPath, TARGET_PACKAGE);

            Path targetDir = workspaceDir.resolve(baseDirRelPath).resolve(TARGET_PACKAGE.replace(".", "/"));
            Files.createDirectories(targetDir);

            for (Path sourceFile : targetJavaFiles) {
                try {
                    String originalContent = Files.readString(sourceFile);
                    String processedContent = processJavaFileContent(originalContent, projectClassNames);

                    Path targetFile = targetDir.resolve(sourceFile.getFileName().toString());
                    if (!sourceFile.toAbsolutePath().equals(targetFile.toAbsolutePath())) {
                        Files.writeString(targetFile, processedContent);
                        Files.delete(sourceFile);
                        log.info("[ScoringService] Moved and normalized: {} -> {}", sourceFile, targetFile);
                    } else {
                        Files.writeString(sourceFile, processedContent);
                        log.info("[ScoringService] Normalized in-place: {}", sourceFile);
                    }
                } catch (Exception e) {
                    log.error("[ScoringService] Failed to process file: {}", sourceFile, e);
                }
            }
        } catch (IOException e) {
            log.error("[ScoringService] Package normalization failed for path: {}", baseDirRelPath, e);
        }
    }

    private String processJavaFileContent(String originalContent, java.util.Set<String> projectClassNames) {
        // 1. 패키지 선언 치환
        String normalizedContent = originalContent.replaceAll("(?m)^\\s*package\\s+[^;]+;", "package " + TARGET_PACKAGE + ";");
        if (!normalizedContent.contains("package " + TARGET_PACKAGE + ";")) {
            normalizedContent = "package " + TARGET_PACKAGE + ";\n" + normalizedContent;
        }

        // 2. 내부 클래스 import 문 주석 처리
        StringBuilder finalContent = new StringBuilder();
        String[] lines = normalizedContent.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                String imp = trimmed.substring(7, trimmed.length() - 1).trim();
                int lastDot = imp.lastIndexOf('.');
                if (lastDot != -1) {
                    String className = imp.substring(lastDot + 1);
                    if (projectClassNames.contains(className)) {
                        finalContent.append("// Redundant internal import removed: ").append(line).append("\n");
                        continue;
                    }
                }
            }
            finalContent.append(line).append("\n");
        }
        return finalContent.toString();
    }

    private void clearOldArtifacts(Path workspaceDir) {
        String[] targets = {"src/test", "build", "bin", "out", "__MACOSX"};
        for (String target : targets) {
            Path targetPath = workspaceDir.resolve(target);
            if (Files.exists(targetPath)) {
                try {
                    org.springframework.util.FileSystemUtils.deleteRecursively(targetPath);
                } catch (IOException e) {
                    log.warn("Failed to delete old artifact: {}", targetPath);
                }
            }
        }
    }

    private void cleanUpEmptyDirectories(Path workspaceDir) {
        try {
            List<Path> directories;
            try (Stream<Path> stream = Files.walk(workspaceDir)) {
                directories = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(workspaceDir))
                    .filter(p -> !p.toString().contains("src")) 
                    .sorted((p1, p2) -> p2.toString().length() - p1.toString().length())
                    .collect(Collectors.toList());
            }

            for (Path dir : directories) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (!s.findAny().isPresent()) {
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
}
