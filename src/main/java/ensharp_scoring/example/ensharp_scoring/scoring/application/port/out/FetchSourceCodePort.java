package ensharp_scoring.example.ensharp_scoring.scoring.application.port.out;

import java.nio.file.Path;

public interface FetchSourceCodePort {
    void fetch(String repoUrl, Path destination);
}
