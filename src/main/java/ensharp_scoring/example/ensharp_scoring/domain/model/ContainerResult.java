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
public class ContainerResult {
    private int exitCode;
    private String logs;
    private String errorLogs;
}
