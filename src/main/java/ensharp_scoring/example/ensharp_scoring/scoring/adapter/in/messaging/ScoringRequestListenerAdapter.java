package ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging;

import ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.dto.ScoringRequestMessage;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.in.ScoreSubmissionUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScoringRequestListenerAdapter implements MessageListener {

    private final ScoreSubmissionUseCase scoreSubmissionUseCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ScoringRequestMessage requestMessage = objectMapper.readValue(message.getBody(), ScoringRequestMessage.class);
            scoreSubmissionUseCase.score(requestMessage.toDomain());
        } catch (Exception e) {
            // 예외 처리 및 로깅 (도메인 정책에 따라 재시도 혹은 DLQ 전송)
        }
    }
}
