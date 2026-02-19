package ensharp_scoring.example.ensharp_scoring.infra.docker;

import ensharp_scoring.example.ensharp_scoring.application.port.out.DockerPort;
import ensharp_scoring.example.ensharp_scoring.domain.model.ContainerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ProcessBuilderAdapter implements DockerPort {

    @Override
    public ContainerResult execute(Path directory, String submissionId) {
        String containerName = "submission-" + submissionId;
        List<String> command = new DockerCommandBuilder()
                .removeAfterExit()
                .name(containerName)
                .network("none")
                .limitCpu(1.0)
                .limitMemory("512m")
                .volume(directory.toAbsolutePath().toString(), "/app")
                .workDir("/app")
                .image("gradle:jdk21")
                .argument("gradle")
                .argument("test")
                .build();

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(directory.toFile());

        // 1. Merge stdout and stderr to avoid deadlock
        processBuilder.redirectErrorStream(true);

        // 2. Redirect output to file to avoid OOM
        Path logFile = directory.resolve("docker_logs.txt");
        processBuilder.redirectOutput(logFile.toFile());

        Process process = null;
        try {
            process = processBuilder.start();

            // 3. Proper timeout handling on the process itself
            boolean completed = process.waitFor(5, TimeUnit.MINUTES);

            if (!completed) {
                process.destroyForcibly();
                log.error("Docker execution timed out for {}", submissionId);
                return ContainerResult.builder()
                        .exitCode(-1)
                        .logs(readLogsFromFile(logFile)) // Read partial logs if needed
                        .errorLogs("Execution Timed Out")
                        .build();
            }

            int exitCode = process.exitValue();
            String logs = readLogsFromFile(logFile);

            return ContainerResult.builder()
                    .exitCode(exitCode)
                    .logs(logs)
                    .errorLogs("") // Stderr is merged into stdout (logs)
                    .build();

        } catch (IOException | InterruptedException e) {
            log.error("Docker execution failed", e);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return ContainerResult.builder()
                    .exitCode(-1)
                    .logs("")
                    .errorLogs("Internal Server Error: " + e.getMessage())
                    .build();
        }
    }

    private String readLogsFromFile(Path logFile) {
        try {
            if (!java.nio.file.Files.exists(logFile)) {
                return "";
            }
            // Read max 1MB or last N lines to prevent OOM even when reading back
            // For now, simple read string but realistically should limit size
            return java.nio.file.Files.readString(logFile);
        } catch (IOException e) {
            log.error("Failed to read log file", e);
            return "Failed to read logs";
        }
    }
}
