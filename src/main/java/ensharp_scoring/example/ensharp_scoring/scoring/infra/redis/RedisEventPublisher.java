package ensharp_scoring.example.ensharp_scoring.scoring.infra.redis;

import ensharp_scoring.example.ensharp_scoring.scoring.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisEventPublisher implements EventPublisher {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void publishResult(String requestInfo, Object result) {
        // TODO: Implement result publishing logic (e.g., to a specific channel)
        log.info("Publishing result for {}: {}", requestInfo, result);
        // redisTemplate.convertAndSend("scoring-result", result.toString());
    }

    @Override
    public void publishError(String requestInfo, String errorMessage) {
        log.error("Publishing error for {}: {}", requestInfo, errorMessage);
        // redisTemplate.convertAndSend("scoring-error", errorMessage);
    }
}
