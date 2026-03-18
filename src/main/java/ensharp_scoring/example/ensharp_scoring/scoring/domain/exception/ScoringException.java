package ensharp_scoring.example.ensharp_scoring.scoring.domain.exception;

public class ScoringException extends RuntimeException {
    public ScoringException(String message) {
        super(message);
    }
    
    public ScoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
