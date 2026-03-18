package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.messaging;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.messaging.dto.ScoringResultMessage;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScoringResultPublisherAdapter implements PublishScoringResultPort {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String RESULT_QUEUE = "scoring.result.queue";

    @Override
    public void publish(ScoringResult result) {
        try {
            ScoringResultMessage message = ScoringResultMessage.from(result);
            String jsonMessage = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(RESULT_QUEUE, jsonMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish scoring result message to Redis", e);
        }
    }
}
