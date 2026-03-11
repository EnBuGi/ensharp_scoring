package ensharp_scoring.example.ensharp_scoring.scoring.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private ExecuteScoringPort executeScoringPort;

    @Mock
    private PublishScoringResultPort publishScoringResultPort;

    @InjectMocks
    private ScoringService scoringService;

    private ScoringRequest createScoringRequest() {
        return ScoringRequest.builder()
                .submissionId("sub-123")
                .repoUrl("https://github.com/repo")
                .testCaseUrl("https://s3/test.zip")
                .timeLimitMs(2000L)
                .memoryLimitMb(512)
                .build();
    }

    @Test
    @DisplayName("시나리오: 채점 실행 포트가 결과를 반환하면, 그 결과를 퍼블리시 포트를 통해 발행해야 한다.")
    void 정상적인_채점_요청시_결과를_발행한다() {
        // Given
        ScoringRequest request = createScoringRequest();
        ScoringResult mockResult = ScoringResult.builder()
                .submissionId("sub-123")
                .overallStatus(ScoringStatus.AC)
                .build();

        given(executeScoringPort.execute(request)).willReturn(mockResult);

        // When
        scoringService.score(request);

        // Then
        verify(executeScoringPort).execute(request);
        verify(publishScoringResultPort).publish(mockResult);
    }

    @Test
    @DisplayName("시나리오: 채점 실행 중 ScoringException 예외 발생 시 과정이 중단되고 예외가 던져져야 한다.")
    void 채점_실행_실패시_예외를_던지고_발행하지_않는다() {
        // Given
        ScoringRequest request = createScoringRequest();
        doThrow(new ScoringException("Docker execution failed", new RuntimeException()))
                .when(executeScoringPort).execute(any(ScoringRequest.class));

        // When & Then
        assertThatThrownBy(() -> scoringService.score(request))
                .isInstanceOf(ScoringException.class)
                .hasMessageContaining("Docker execution failed");

        verify(publishScoringResultPort, never()).publish(any(ScoringResult.class));
    }
}
