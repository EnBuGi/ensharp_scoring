package ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.dto.ScoringRequestMessage;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.in.ScoreSubmissionUseCase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoringQueueListener {

    private final ScoreSubmissionUseCase scoreSubmissionUseCase;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private static final String SUBMISSION_QUEUE = "oj:submission:queue";

    @Value("${scoring.worker.threads:4}")
    private int workerThreads;

    private ExecutorService executorService;

    @PostConstruct
    public void startListening() {
        log.info("Starting ScoringQueueListener with {} threads", workerThreads);
        executorService = Executors.newFixedThreadPool(workerThreads);
        for (int i = 0; i < workerThreads; i++) {
            int threadId = i;
            executorService.submit(() -> pollQueue(threadId));
        }
    }

    @PreDestroy
    public void stopListening() {
        log.info("Stopping ScoringQueueListener...");
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    private void pollQueue(int threadId) {
        String threadName = "ScoringWorker-" + threadId;
        Thread.currentThread().setName(threadName);
        log.info("Worker {} started polling scoring queue: {}", threadName, SUBMISSION_QUEUE);
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // block for up to 5 seconds (must be less than redis command timeout)
                String payload = stringRedisTemplate.opsForList().leftPop(SUBMISSION_QUEUE, 5, TimeUnit.SECONDS);

                if (payload != null) {
                    processMessage(payload, threadName);
                }
            } catch (Exception e) {
                log.error("Error in worker {}: polling scoring queue failed", threadName, e);
                // pause briefly on error to avoid tight loop
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt(); // Restore interrupt status
                }
            }
        }
    }

    private void processMessage(String payload, String workerName) {
        try {
            ScoringRequestMessage requestMessage = objectMapper.readValue(payload, ScoringRequestMessage.class);
            log.info("Worker {} received scoring request: submissionId={}", workerName, requestMessage.submissionId());
            scoreSubmissionUseCase.score(requestMessage.toDomain());
        } catch (Exception e) {
            log.error("Worker {} failed to process scoring request", workerName, e);
        }
    }
}
