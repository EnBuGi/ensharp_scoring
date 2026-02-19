package ensharp_scoring.example.ensharp_scoring.infra.docker;

import java.util.ArrayList;
import java.util.List;

public class DockerCommandBuilder {
    private final List<String> command = new ArrayList<>();

    public DockerCommandBuilder() {
        command.add("docker");
        command.add("run");
    }

    public DockerCommandBuilder removeAfterExit() {
        command.add("--rm");
        return this;
    }

    public DockerCommandBuilder name(String name) {
        command.add("--name");
        command.add(name);
        return this;
    }

    public DockerCommandBuilder network(String network) {
        command.add("--network");
        command.add(network);
        return this;
    }

    public DockerCommandBuilder limitCpu(double cpus) {
        command.add("--cpus");
        command.add(String.valueOf(cpus));
        return this;
    }

    public DockerCommandBuilder limitMemory(String memory) {
        command.add("--memory");
        command.add(memory);
        return this;
    }

    public DockerCommandBuilder volume(String hostPath, String containerPath) {
        command.add("-v");
        command.add(hostPath + ":" + containerPath);
        return this;
    }

    public DockerCommandBuilder workDir(String workDir) {
        command.add("-w");
        command.add(workDir);
        return this;
    }

    public DockerCommandBuilder image(String image) {
        command.add(image);
        return this;
    }

    public DockerCommandBuilder argument(String arg) {
        command.add(arg);
        return this;
    }

    public List<String> build() {
        return new ArrayList<>(command);
    }
}
