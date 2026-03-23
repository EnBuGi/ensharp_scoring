package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.git;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchSourceCodePort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
public class JGitAdapter implements FetchSourceCodePort {

    @Override
    public void fetch(String repoUrl, Path destination, String githubAccessToken) {
        log.info("[JGitAdapter] Fetching from repo: {}, destination: {}", repoUrl, destination);
        
        try {
            var cloneCommand = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(destination.toFile())
                    .setCloneAllBranches(false)
                    .setDepth(1);

            if (githubAccessToken != null && !githubAccessToken.isBlank()) {
                log.info("[JGitAdapter] Using githubAccessToken (length: {})", githubAccessToken.length());
                // For GitHub HTTPS, the token is technically the password. The username can be anything.
                // Using the token as username and blank as password is a common practice.
                cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubAccessToken, ""));
            } else {
                log.warn("[JGitAdapter] No githubAccessToken provided for repo: {}", repoUrl);
            }

            cloneCommand.call().close();
            log.info("[JGitAdapter] Successfully cloned repository: {}", repoUrl);
        } catch (GitAPIException e) {
            log.error("[JGitAdapter] GitAPIException during fetch: {}", e.getMessage());
            throw new ScoringException("Failed to clone github repository at " + repoUrl, e);
        }
    }
}
