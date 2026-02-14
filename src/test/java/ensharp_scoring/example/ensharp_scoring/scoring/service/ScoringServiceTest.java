package ensharp_scoring.example.ensharp_scoring.scoring.service;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringInfo;
import ensharp_scoring.example.ensharp_scoring.scoring.event.EventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private BuildRunner buildRunner;

    @Mock
    private TestResultParser testResultParser;

    @InjectMocks
    private ScoringService scoringService;

    @Test
    void 채점시_정상적으로_채점한다() {
        // Given
        ScoringInfo info = new ScoringInfo("https://github.com/user/repo", "problem-1");

        // When
        scoringService.score(info);

        // Then
        verify(gitRepositoryManager).cloneOrPull(info.repositoryUrl());
        verify(buildRunner).runTests(any());
        verify(testResultParser).parse(any());
        verify(eventPublisher).publishResult(any(), any());
    }

    @Test
    void 채점시_빌드실패하면_에러를_발행한다() {
        // Given
        ScoringInfo info = new ScoringInfo("https://github.com/user/repo", "problem-1");
        org.mockito.BDDMockito.willThrow(new RuntimeException("Build failed"))
                .given(buildRunner).runTests(any());

        // When
        scoringService.score(info);

        // Then
        verify(eventPublisher).publishError(any(), any());
        verify(eventPublisher, org.mockito.Mockito.never()).publishResult(any(), any());
    }
}
