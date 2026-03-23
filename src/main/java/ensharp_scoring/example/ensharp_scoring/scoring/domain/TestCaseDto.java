package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseDto {
    private String id;
    private String name;
    private int score;
    private boolean isHidden;
}
