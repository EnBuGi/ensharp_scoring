package ensharp_scoring.example.ensharp_scoring.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private ScoringService scoringService;

    @Captor
    private ArgumentCaptor<ScoringResult> resultCaptor;

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
    @DisplayName("시나리오: 정상 과정 (코드 페치 -> 케이스 페치 -> 실행 -> 결과 발행)")
    void 정상적인_채점_요청시_결과를_발행한다() {
        // Given
        ScoringRequest request = createScoringRequest();
        ScoringResult mockResult = ScoringResult.builder()
                .submissionId("sub-123")
                .overallStatus(ScoringStatus.ACCEPTED)
                .build();

        given(executeScoringPort.execute(request)).willReturn(mockResult);

        // When
        scoringService.score(request);

        // Then
        verify(fetchSourceCodePort).fetch(eq("https://github.com/repo"), any(Path.class), any());
        verify(fetchTestCasePort).fetch(eq("https://s3/test.zip"), any(Path.class));
        verify(executeScoringPort).execute(request);
        verify(publishScoringResultPort).publish(mockResult);
    }

    @Test
    @DisplayName("시나리오: 코드 페치 혹은 케이스 페치 시점에 예외 발생 시 Runtime Error (RE) 결과가 발행되어야 한다")
    void 페치_실패시_채점결과로_RE를_발행한다() {
        // Given
        ScoringRequest request = createScoringRequest();

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
    void 범용에러_실패시_채점결과로_EE를_발행한다() {
        // Given
        ScoringRequest request = createScoringRequest();

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
