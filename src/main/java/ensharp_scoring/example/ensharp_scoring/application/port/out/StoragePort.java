package ensharp_scoring.example.ensharp_scoring.application.port.out;

import java.nio.file.Path;

public interface StoragePort {
    void downloadTestCode(String url, Path destination);
}
