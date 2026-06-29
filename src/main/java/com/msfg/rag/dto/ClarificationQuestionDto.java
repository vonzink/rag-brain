package com.msfg.rag.dto;

import java.util.List;

public record ClarificationQuestionDto(
        String question,
        List<String> missingFacts
) {
}
