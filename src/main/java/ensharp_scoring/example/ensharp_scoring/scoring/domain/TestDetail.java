package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TestDetail {
    private final String methodName;
    private final String status;
    private final long durationMs;
    private final String message;
}
