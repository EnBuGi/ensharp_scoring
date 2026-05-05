package ensharp_scoring.example.ensharp_scoring.scoring.application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.in.ScoreSubmissionUseCase;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchSourceCodePort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchTestCasePort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ScoringService implements ScoreSubmissionUseCase {

    private final FetchSourceCodePort fetchSourceCodePort;
    private final FetchTestCasePort fetchTestCasePort;
    private final ExecuteScoringPort executeScoringPort;
    private final PublishScoringResultPort publishScoringResultPort;
    
    private final String workspaceBaseDir;

    @org.springframework.beans.factory.annotation.Autowired
    public ScoringService(
            FetchSourceCodePort fetchSourceCodePort,
            FetchTestCasePort fetchTestCasePort,
            ExecuteScoringPort executeScoringPort,
            PublishScoringResultPort publishScoringResultPort,
            @org.springframework.beans.factory.annotation.Value("${scoring.workspace.base-dir:/tmp/workspace}") String workspaceBaseDir) {
        this.fetchSourceCodePort = fetchSourceCodePort;
        this.fetchTestCasePort = fetchTestCasePort;
        this.executeScoringPort = executeScoringPort;
        this.publishScoringResultPort = publishScoringResultPort;
        this.workspaceBaseDir = workspaceBaseDir;
    }

    private static final String TARGET_PACKAGE = "gs.submission";


    @Override
    public void score(ScoringRequest request) {
        String submissionId = request.getSubmissionId();
        Path workspaceDir = Paths.get(workspaceBaseDir, submissionId);
        
        try {
            // 1. 작업 디렉토리 생성
            Files.createDirectories(workspaceDir);
            
            // 2. 깃허브 코드 클론
            fetchSourceCodePort.fetch(request.getRepoUrl(), workspaceDir, request.getGithubAccessToken());
            
            // 권한 설정
            setWorkspacePermissions(workspaceDir);

            // 기존 아티팩트 제거
            clearOldArtifacts(workspaceDir);

            // 3. [Conditional] 패키지 정규화 (순수 Java 과제일 때만 수행)
            // Spring Boot 과제는 패키지 구조가 중요하므로 정규화를 수행하지 않음
            if ("JAVA".equalsIgnoreCase(request.getProjectType())) {
                log.info("[ScoringService] Normalizing package structure to {} for JAVA project", TARGET_PACKAGE);
                normalizePackageStructure(workspaceDir);
            } else {
                log.info("[ScoringService] Skipping package normalization for SPRING project to preserve structure");
            }

            // 4. 테스트 케이스 다운로드 및 압축 해제
            if (request.getTestCodeUrl() == null || request.getTestCodeUrl().isBlank()) {
                throw new ScoringException("Test case URL is missing for submission: " + submissionId);
            }

            fetchTestCasePort.fetch(request.getTestCodeUrl(), workspaceDir);
            setWorkspacePermissions(workspaceDir);

            // 테스트 코드는 제공된 구조 그대로 사용 (gs.submission 패키지 준수 가정)
            log.info("[ScoringService] Successfully fetched test cases");
            
            // 5. 프로젝트 파일(Gradle, H2 Config 등) 생성
            generateProjectFiles(workspaceDir, request.getProjectType());
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
                .buildLog("Scoring exception: " + e.getMessage())
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
                .buildLog("Unexpected error: " + e.getMessage())
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

    private void normalizePackageStructure(Path workspaceDir) {
        try {
            // 리소스 파일 정리 (src/main/resources로 이동)
            organizeResources(workspaceDir);

            // 소스 코드 정규화
            Path mainSourceDir = workspaceDir.resolve("src/main/java");
            if (Files.exists(mainSourceDir)) {
                normalizePackage(mainSourceDir, TARGET_PACKAGE);
            }

            // 테스트 코드 정규화 (제출물 내부에 테스트가 포함된 경우)
            Path testSourceDir = workspaceDir.resolve("src/test/java");
            if (Files.exists(testSourceDir)) {
                normalizePackage(testSourceDir, TARGET_PACKAGE);
            }

            log.info("[ScoringService] Package normalization complete.");
        } catch (Exception e) {
            log.error("[ScoringService] Error during package normalization", e);
            throw new ScoringException("소스 코드 전처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private void normalizePackage(Path sourceBaseDir, String targetPackage) throws IOException {
        Path targetDir = sourceBaseDir.resolve(targetPackage.replace(".", "/"));
        Files.createDirectories(targetDir);

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceBaseDir)) {
            javaFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                // 이미 타겟 디렉토리에 있는 파일은 제외
                .filter(p -> !p.getParent().equals(targetDir))
                .collect(Collectors.toList());
        }

        log.info("[ScoringService] Found {} java files to normalize in {}", javaFiles.size(), sourceBaseDir);

        for (Path file : javaFiles) {
            log.info("[ScoringService] Normalizing file: {}", file);
            String content = Files.readString(file);
            
            // 1. 기존 패키지명 추출 및 정규화
            String originalPackage = "";
            java.util.regex.Pattern pkgPattern = java.util.regex.Pattern.compile("package\\s+([a-zA-Z0-9._]+);");
            java.util.regex.Matcher pkgMatcher = pkgPattern.matcher(content);
            if (pkgMatcher.find()) {
                originalPackage = pkgMatcher.group(1);
                content = pkgMatcher.replaceAll("package " + targetPackage + ";");
            } else {
                content = "package " + targetPackage + ";\n\n" + content;
            }

            // 2. 내부 패키지 import 제거 (단일 패키지로 합쳤으므로)
            if (!originalPackage.isEmpty()) {
                content = content.replaceAll("import\\s+" + originalPackage + "\\.[a-zA-Z0-9._*]+;", "");
            }
            // 추가로 gs.submission 하위 import도 제거 (안전장치)
            content = content.replaceAll("import\\s+" + targetPackage + "\\.[a-zA-Z0-9._*]+;", "");

            Path targetFile = targetDir.resolve(file.getFileName());
            log.info("[ScoringService] Writing normalized file to: {}", targetFile);
            
            // 파일명이 겹칠 경우 (다른 패키지의 같은 클래스명) - 일단 덮어씀 (코딩테스트 특성상 드묾)
            Files.writeString(targetFile, content);
            
            if (!file.equals(targetFile)) {
                log.info("[ScoringService] Deleting original file: {}", file);
                Files.delete(file);
            }
        }

        // 빈 디렉토리 정리
        cleanEmptyDirectories(sourceBaseDir, targetDir);
    }

    private void cleanEmptyDirectories(Path root, Path exclude) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> dirs = stream
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(root))
                .filter(p -> !p.startsWith(exclude))
                .sorted((p1, p2) -> p2.toString().length() - p1.toString().length()) // 하위 디렉토리부터
                .collect(Collectors.toList());

            for (Path dir : dirs) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (s.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        }
    }

    private void organizeResources(Path workspaceDir) throws IOException {
        Path targetMainResources = workspaceDir.resolve("src/main/resources");
        Files.createDirectories(targetMainResources);

        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            List<Path> resourceFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().startsWith("._"))
                .filter(p -> !p.toString().contains("__MACOSX"))
                .filter(p -> !p.toString().contains("src/test"))
                .filter(p -> !p.toString().contains("src/main/resources"))
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".xml") || name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json");
                })
                .collect(Collectors.toList());

            for (Path f : resourceFiles) {
                Path targetPath = targetMainResources.resolve(f.getFileName().toString());
                if (!f.equals(targetPath)) {
                    Files.move(f, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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

    private void setWorkspacePermissions(Path workspaceDir) {
        log.info("[ScoringService] Setting workspace permissions: {}", workspaceDir);
        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            stream.forEach(p -> {
                File file = p.toFile();
                file.setWritable(true, false);
                file.setReadable(true, false);
                file.setExecutable(true, false);
            });
        } catch (IOException e) {
            log.error("[ScoringService] Error setting permissions", e);
        }
    }

    private void generateProjectFiles(Path workspaceDir, String projectType) throws Exception {
        Files.writeString(workspaceDir.resolve("settings.gradle"), "rootProject.name = 'submission'\n");
        
        String propertiesContent = "org.gradle.daemon=false\norg.gradle.jvmargs=-Xmx256m\n";
        Files.writeString(workspaceDir.resolve("gradle.properties"), propertiesContent);

        String templatePath = "SPRING".equalsIgnoreCase(projectType) ? "templates/spring-build.gradle" : "templates/java-build.gradle";
        try (var inputStream = new org.springframework.core.io.ClassPathResource(templatePath).getInputStream()) {
            String template = org.springframework.util.StreamUtils.copyToString(inputStream, java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(workspaceDir.resolve("build.gradle"), template);
        }

        generateH2Configuration(workspaceDir);
    }

    private void generateH2Configuration(Path workspaceDir) throws IOException {
        Path testResourceDir = workspaceDir.resolve("src/test/resources");
        Files.createDirectories(testResourceDir);
        
        String h2Config = "spring:\n" +
                "  datasource:\n" +
                "    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1\n" +
                "    driver-class-name: org.h2.Driver\n" +
                "    username: root\n" +
                "    password: \n" +
                "  jpa:\n" +
                "    hibernate:\n" +
                "      ddl-auto: update\n" +
                "    show-sql: true\n" +
                "  h2:\n" +
                "    console:\n" +
                "      enabled: true\n";
        
        Files.writeString(testResourceDir.resolve("application.yml"), h2Config);
        log.info("[ScoringService] Generated H2 configuration in src/test/resources/application.yml");
    }
}
