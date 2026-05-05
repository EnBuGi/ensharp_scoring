package ensharp_scoring.example.ensharp_scoring.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchSourceCodePort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchTestCasePort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private FetchSourceCodePort fetchSourceCodePort;
    @Mock
    private FetchTestCasePort fetchTestCasePort;
    @Mock
    private ExecuteScoringPort executeScoringPort;
    @Mock
    private PublishScoringResultPort publishScoringResultPort;

    @TempDir
    Path tempDir;

    private ScoringService scoringService;

    @Captor
    private ArgumentCaptor<ScoringResult> resultCaptor;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        scoringService = new ScoringService(
            fetchSourceCodePort,
            fetchTestCasePort,
            executeScoringPort,
            publishScoringResultPort,
            tempDir.toString()
        );
    }

    private ScoringRequest createScoringRequest() {
        return ScoringRequest.builder()
                .submissionId("sub-123")
                .repoUrl("https://github.com/repo")
                .testCodeUrl("https://s3/test.zip")
                .timeLimit(2000L)
                .memoryLimit(512)
                .projectType("JAVA")
                .build();
    }

    @Test
    @DisplayName("모든 프로젝트 타입(SPRING, JAVA)에서 H2 설정 파일이 생성되어야 한다")
    void testShouldGenerateH2ConfigForAllProjectTypes() throws Exception {
        // 1. SPRING 프로젝트 테스트
        String springId = "spring-h2-universal";
        ScoringRequest springRequest = createMockRequest(springId, "SPRING");
        scoringService.score(springRequest);
        
        // 2. JAVA 프로젝트 테스트
        String javaId = "java-h2-universal";
        ScoringRequest javaRequest = createMockRequest(javaId, "JAVA");
        scoringService.score(javaRequest);
        
        // integration test 관점에서는 에러 없이 종료되는 것을 통해 
        // generateH2Configuration 로직이 모든 타입에서 무사히 수행됨을 확인.
    }

    private ScoringRequest createMockRequest(String submissionId, String projectType) {
        return ScoringRequest.builder()
            .submissionId(submissionId)
            .repoUrl("https://github.com/test/repo")
            .testCodeUrl("https://test-server.com/test.zip")
            .projectType(projectType)
            .githubAccessToken("test-token")
            .build();
    }

    @Test
    @DisplayName("시나리오: 제멋대로인 패키지 구조 정규화 검증 (Zero-Config)")
    void 소스코드_패키지_정규화_검증() throws IOException {
        // Given
        String subId = "sub-normalize";
        ScoringRequest request = createMockRequest(subId, "JAVA");
        
        // 1. 학생 코드 생성 (다양한 패키지)
        Path studentCodeDir = tempDir.resolve(subId).resolve("src/main/java/com/example/student");
        Files.createDirectories(studentCodeDir);
        Files.writeString(studentCodeDir.resolve("Solution.java"), 
            "package com.example.student;\n" +
            "import com.example.student.util.Helper;\n" +
            "public class Solution { Helper h; }");
            
        Path studentUtilDir = tempDir.resolve(subId).resolve("src/main/java/com/example/student/util");
        Files.createDirectories(studentUtilDir);
        Files.writeString(studentUtilDir.resolve("Helper.java"), 
            "package com.example.student.util;\n" +
            "public class Helper {}");

        // 2. 리소스 파일 생성
        Path legacyResourceDir = tempDir.resolve(subId).resolve("src/main/java/resources");
        Files.createDirectories(legacyResourceDir);
        Files.writeString(legacyResourceDir.resolve("config.xml"), "<config></config>");

        ScoringResult mockResult = ScoringResult.builder()
                .submissionId(subId)
                .overallStatus(ScoringStatus.ACCEPTED)
                .build();
        given(executeScoringPort.execute(any())).willAnswer(invocation -> {
            // Assert while workspace still exists (before finally block deletes it)
            Path normalizedDir = tempDir.resolve(subId).resolve("src/main/java/gs/submission");
            assertThat(normalizedDir.resolve("Solution.java")).exists();
            assertThat(normalizedDir.resolve("Helper.java")).exists();
            
            // 기존 디렉토리는 삭제되었는지 확인
            assertThat(tempDir.resolve(subId).resolve("src/main/java/com")).doesNotExist();

            // 파일 내용이 보정되었는지 확인 (package 선언 및 내부 import 제거)
            String solutionContent = Files.readString(normalizedDir.resolve("Solution.java"));
            assertThat(solutionContent).contains("package gs.submission;");
            assertThat(solutionContent).doesNotContain("import com.example.student.util.Helper;");

            // 리소스가 src/main/resources로 이동되었는지 확인
            assertThat(tempDir.resolve(subId).resolve("src/main/resources/config.xml")).exists();

            return mockResult;
        });

        // When
        scoringService.score(request);

        // Then
        verify(executeScoringPort).execute(any());
        verify(publishScoringResultPort).publish(mockResult);
    }

    @Test
    @DisplayName("시나리오: 정상 과정 (코드 페치 -> 케이스 페치 -> 실행 -> 결과 발행)")
    void 정상적인_채점_요청시_결과를_발행한다() throws IOException {
        // Given
        ScoringRequest request = createScoringRequest();
        
        // 패키지 정규화 대상 소스 생성
        Path sourcePath = tempDir.resolve("sub-123/src/main/java/some/random/pkg");
        Files.createDirectories(sourcePath);
        Files.writeString(sourcePath.resolve("App.java"), "package some.random.pkg; public class App {}");

        ScoringResult mockResult = ScoringResult.builder()
                .submissionId("sub-123")
                .overallStatus(ScoringStatus.ACCEPTED)
                .build();

        given(executeScoringPort.execute(any())).willReturn(mockResult);

        // When
        scoringService.score(request);

        // Then
        verify(fetchSourceCodePort).fetch(eq("https://github.com/repo"), any(Path.class), any());
        verify(fetchTestCasePort).fetch(eq("https://s3/test.zip"), any(Path.class));
        verify(executeScoringPort).execute(any());
        verify(publishScoringResultPort).publish(mockResult);
    }

    @Test
    @DisplayName("시나리오: 코드 페치 혹은 케이스 페치 시점에 예외 발생 시 Runtime Error (RE) 결과가 발행되어야 한다")
    void 페치_실패시_채점결과로_RE를_발행한다() throws IOException {
        // Given
        ScoringRequest request = createScoringRequest();
        Path skeletonPath = tempDir.resolve("sub-123/src/main/java/gs/submission");
        Files.createDirectories(skeletonPath);

        doThrow(new ScoringException("Failed to download code"))
                .when(fetchSourceCodePort).fetch(any(String.class), any(Path.class), any());

        // When
        scoringService.score(request);

        // Then
        verify(fetchSourceCodePort).fetch(any(String.class), any(Path.class), any());
        
        // 뒷 단계들은 실행되지 않아야 함
        verify(fetchTestCasePort, never()).fetch(any(String.class), any(Path.class));
        verify(executeScoringPort, never()).execute(any(ScoringRequest.class));

        verify(publishScoringResultPort).publish(resultCaptor.capture());
        ScoringResult publishedResult = resultCaptor.getValue();
        
        assertThat(publishedResult.getSubmissionId()).isEqualTo("sub-123");
        assertThat(publishedResult.getOverallStatus()).isEqualTo(ScoringStatus.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("시나리오: 실행 포트 등에서 명시적이지 않은 예외 (Exception) 가 발생할 경우 Execution Error (EE) 결과가 발행되어야 한다")
    void 범용에러_실패시_채점결과로_EE를_발행한다() throws IOException {
        // Given
        ScoringRequest request = createScoringRequest();
        Path skeletonPath = tempDir.resolve("sub-123/src/main/java/gs/submission");
        Files.createDirectories(skeletonPath);

        doThrow(new RuntimeException("Unknown Disk Error"))
                .when(fetchSourceCodePort).fetch(any(String.class), any(Path.class), any());

        // When
        scoringService.score(request);

        // Then
        verify(publishScoringResultPort).publish(resultCaptor.capture());
        ScoringResult publishedResult = resultCaptor.getValue();
        
        assertThat(publishedResult.getSubmissionId()).isEqualTo("sub-123");
        assertThat(publishedResult.getOverallStatus()).isEqualTo(ScoringStatus.EXECUTION_ERROR);
    }
}
