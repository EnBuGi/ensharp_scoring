package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.messaging.dto;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestDetail;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TestDetailMessage {
    private String methodName;
    private String status;
    private long durationMs;
    private String message;

    public static TestDetailMessage from(TestDetail domain) {
        return TestDetailMessage.builder()
                .methodName(domain.getMethodName())
                .status(domain.getStatus())
                .durationMs(domain.getDurationMs())
                .message(domain.getMessage())
                .build();
    }
}
