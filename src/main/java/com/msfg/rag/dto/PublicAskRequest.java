package com.msfg.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record PublicAskRequest(
        @NotBlank @Size(max = 255) String sessionId,
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 200) String pageRoute,
        @Size(max = 20) String surface,
        Map<String, Object> facts
) {
}
