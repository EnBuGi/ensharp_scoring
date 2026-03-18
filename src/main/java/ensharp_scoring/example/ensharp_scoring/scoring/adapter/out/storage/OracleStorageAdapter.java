package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.storage;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchTestCasePort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class OracleStorageAdapter implements FetchTestCasePort {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void fetch(String testCodeUrl, Path destination) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testCodeUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new ScoringException("Failed to download test case. HTTP Status: " + response.statusCode());
            }

            try (ZipInputStream zis = new ZipInputStream(response.body())) {
                ZipEntry zipEntry = zis.getNextEntry();
                while (zipEntry != null) {
                    Path newPath = destination.resolve(zipEntry.getName()).normalize();
                    if (!newPath.startsWith(destination)) {
                        throw new IOException("Zip entry is outside of the target dir: " + zipEntry.getName());
                    }

                    if (zipEntry.isDirectory()) {
                        Files.createDirectories(newPath);
                    } else {
                        Files.createDirectories(newPath.getParent());
                        Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zipEntry = zis.getNextEntry();
                }
            }

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScoringException("Failed to download or unzip test cases", e);
        }
    }
}
