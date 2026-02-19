package ensharp_scoring.example.ensharp_scoring.application.port.out;

import ensharp_scoring.example.ensharp_scoring.domain.model.ContainerResult;
import java.nio.file.Path;

public interface DockerPort {
    /**
     * @return ExecutionResult (ExitCode, StandardOutput, ErrorOutput)
     */
    ContainerResult execute(Path directory, String submissionId);
}
