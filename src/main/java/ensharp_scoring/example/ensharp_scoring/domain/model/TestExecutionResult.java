package ensharp_scoring.example.ensharp_scoring.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TestExecutionResult {
    private String method;
    private boolean passed;
    private String message;
    private long duration;

    public static TestExecutionResult buildError(String message) {
        return TestExecutionResult.builder()
                .method("BUILD_ERROR")
                .passed(false)
                .message(message)
                .duration(0)
                .build();
    }
}
