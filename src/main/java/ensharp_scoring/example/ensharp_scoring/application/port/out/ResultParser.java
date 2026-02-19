package ensharp_scoring.example.ensharp_scoring.application.port.out;

import ensharp_scoring.example.ensharp_scoring.domain.model.TestExecutionResult;
import java.nio.file.Path;
import java.util.List;

public interface ResultParser {
    List<TestExecutionResult> parse(Path resultPath);
}
