package ensharp_scoring.example.ensharp_scoring.domain.model;

import lombok.Builder;

@Builder
public record TestExecutionResult(
    String method,
    boolean passed,
    String message,
    long duration
) {
    public static TestExecutionResult buildError(String message) {
        return TestExecutionResult.builder()
                .method("BUILD_ERROR")
                .passed(false)
                .message(message)
                .duration(0)
                .build();
    }
}
