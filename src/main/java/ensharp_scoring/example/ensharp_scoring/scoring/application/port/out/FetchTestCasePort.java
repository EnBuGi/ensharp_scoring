package ensharp_scoring.example.ensharp_scoring.scoring.application.port.out;

import java.nio.file.Path;

public interface FetchTestCasePort {
    void fetch(String testCaseUrl, Path destination);
}
