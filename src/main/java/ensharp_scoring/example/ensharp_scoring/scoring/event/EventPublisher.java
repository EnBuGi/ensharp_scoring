package ensharp_scoring.example.ensharp_scoring.scoring.event;

public interface EventPublisher {
    void publishResult(String requestInfo, Object result); // TODO: Define specific Result object

    void publishError(String requestInfo, String errorMessage);
}
