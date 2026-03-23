package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.git;

import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.FetchSourceCodePort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.exception.ScoringException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class JGitAdapter implements FetchSourceCodePort {

    @Override
    public void fetch(String repoUrl, Path destination, String githubAccessToken) {
        try {
            var cloneCommand = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(destination.toFile())
                    .setCloneAllBranches(false)
                    .setDepth(1);

            if (githubAccessToken != null && !githubAccessToken.isBlank()) {
                cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubAccessToken, ""));
            }

            cloneCommand.call().close();
        } catch (GitAPIException e) {
            throw new ScoringException("Failed to clone github repository at " + repoUrl, e);
        }
    }
}
