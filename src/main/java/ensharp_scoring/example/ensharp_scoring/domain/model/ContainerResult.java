package ensharp_scoring.example.ensharp_scoring.domain.model;

import lombok.Builder;

@Builder
public record ContainerResult(
        int exitCode,
        String logs,
        String errorLogs) {
}
