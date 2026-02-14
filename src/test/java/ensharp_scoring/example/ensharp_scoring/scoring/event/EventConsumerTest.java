package ensharp_scoring.example.ensharp_scoring.scoring.event;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringInfo;
import ensharp_scoring.example.ensharp_scoring.scoring.service.ScoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    @InjectMocks
    private EventConsumer eventConsumer;

    @Mock
    private ScoringService scoringService;

    @Mock
    private EventPublisher eventPublisher;

    @org.mockito.Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void 정상적인_메시지를_받았을때_정상적으로_스코어링을_진행한다() {
        // Given
        String message = "{\"repositoryUrl\":\"https://github.com/user/repo.git\", \"problemId\":\"123\"}";

        // When
        eventConsumer.receiveMessage(message);

        // Then
        verify(scoringService).score(any(ScoringInfo.class));
    }

    @Test
    void 정상적이지_않은_메시지를_받았을때_에러를_발행한다() {
        // Given
        String message = "invalid-json";

        // When
        eventConsumer.receiveMessage(message);

        // Then
        verify(eventPublisher).publishError(any(), any());
    }
}
