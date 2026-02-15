package ensharp_scoring.example.ensharp_scoring.application.port.out;

import java.nio.file.Path;

public interface GitPort {
    void clone(String url, Path destination);
}
