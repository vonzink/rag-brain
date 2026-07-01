package com.ragbrain.rag.service.retrieval;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A chunk selected by hybrid search, with all scores and citation metadata.
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String content,
        UUID parentChunkId,
        String parentContent,
        String hierarchyPath,
        String sourceName,
        String sourceType,
        String documentName,
        String documentTitle,
        String section,
        Integer pageNumber,
        LocalDate effectiveDate,
        double vectorScore,
        double keywordScore,
        double combinedScore
) {
    public RetrievedChunk(UUID chunkId, UUID documentId, String content,
                          String sourceName, String sourceType, String documentName,
                          String documentTitle, String section, Integer pageNumber,
                          LocalDate effectiveDate, double vectorScore, double keywordScore,
                          double combinedScore) {
        this(chunkId, documentId, content, null, null, null, sourceName, sourceType,
                documentName, documentTitle, section, pageNumber, effectiveDate,
                vectorScore, keywordScore, combinedScore);
    }
}
