package ensharp_scoring.example.ensharp_scoring.infra.git;

import ensharp_scoring.example.ensharp_scoring.application.port.out.GitPort;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
public class JGitAdapter implements GitPort {

    @Override
    public void clone(String url, Path destination) {
        try {
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(destination.toFile())
                    .call();
            log.info("Successfully cloned {} to {}", url, destination);
        } catch (GitAPIException e) {
            log.error("Failed to clone repository: {}", url, e);
            throw new RuntimeException("Git clone failed", e);
        }
    }
}
