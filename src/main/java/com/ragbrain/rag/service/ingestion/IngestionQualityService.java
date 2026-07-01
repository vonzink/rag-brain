package com.ragbrain.rag.service.ingestion;

import com.ragbrain.rag.domain.BrainDocument;
import com.ragbrain.rag.domain.DocumentChunk;
import com.ragbrain.rag.dto.IngestionQualityDto;
import com.ragbrain.rag.repository.BrainDocumentRepository;
import com.ragbrain.rag.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class IngestionQualityService {

    private final BrainDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public IngestionQualityService(BrainDocumentRepository documents, DocumentChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public IngestionQualityDto evaluate(UUID brainId) {
        List<BrainDocument> brainDocuments = documents.findByBrainId(brainId);
        List<DocumentChunk> brainChunks = chunks.findByBrainId(brainId);
        List<IngestionQualityDto.DocumentQualityDto> documentQuality = brainDocuments.stream()
                .sorted(Comparator.comparing(BrainDocument::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(document -> evaluateDocument(document, brainChunks))
                .toList();

        int documentCount = brainDocuments.size();
        int activeDocumentCount = (int) brainDocuments.stream().filter(BrainDocument::isActive).count();
        int chunkCount = brainChunks.size();
        int embeddedChunkCount = count(brainChunks, IngestionQualityService::hasEmbedding);
        int childMissingEmbeddingCount = count(brainChunks,
                chunk -> isChild(chunk) && !hasEmbedding(chunk));
        int parentChunkCount = count(brainChunks, IngestionQualityService::isParent);
        int childChunkCount = count(brainChunks, IngestionQualityService::isChild);
        int orphanChildChunkCount = count(brainChunks, IngestionQualityService::isOrphanChild);
        int emptyChunkCount = count(brainChunks, IngestionQualityService::isEmpty);
        int duplicateChunkTextGroups = duplicateTextGroupCount(brainChunks);
        int missingCitationMetadata = count(brainChunks,
                chunk -> isChild(chunk) && !hasCitationMetadata(chunk));

        List<String> warnings = brainWarnings(documentQuality, documentCount, chunkCount, childMissingEmbeddingCount,
                orphanChildChunkCount, emptyChunkCount, duplicateChunkTextGroups, missingCitationMetadata);

        return new IngestionQualityDto(
                brainId,
                documentCount,
                activeDocumentCount,
                chunkCount,
                embeddedChunkCount,
                childMissingEmbeddingCount,
                parentChunkCount,
                childChunkCount,
                orphanChildChunkCount,
                emptyChunkCount,
                duplicateChunkTextGroups,
                missingCitationMetadata,
                documentQuality,
                warnings);
    }

    private static IngestionQualityDto.DocumentQualityDto evaluateDocument(BrainDocument document,
                                                                           List<DocumentChunk> brainChunks) {
        List<DocumentChunk> documentChunks = brainChunks.stream()
                .filter(chunk -> belongsTo(chunk, document))
                .toList();
        int chunkCount = documentChunks.size();
        int embeddedChunkCount = count(documentChunks, IngestionQualityService::hasEmbedding);
        int childMissingEmbeddingCount = count(documentChunks, chunk -> isChild(chunk) && !hasEmbedding(chunk));
        int parentChunkCount = count(documentChunks, IngestionQualityService::isParent);
        int childChunkCount = count(documentChunks, IngestionQualityService::isChild);
        int orphanChildChunkCount = count(documentChunks, IngestionQualityService::isOrphanChild);
        int emptyChunkCount = count(documentChunks, IngestionQualityService::isEmpty);
        int missingCitationMetadata = count(documentChunks, chunk -> isChild(chunk) && !hasCitationMetadata(chunk));

        List<String> warnings = new ArrayList<>();
        if (chunkCount == 0) {
            warnings.add("Document has no chunks");
        }
        if (childMissingEmbeddingCount > 0) {
            warnings.add("Child chunks missing embeddings: " + childMissingEmbeddingCount);
        }
        if (orphanChildChunkCount > 0) {
            warnings.add("Orphan child chunks: " + orphanChildChunkCount);
        }
        if (emptyChunkCount > 0) {
            warnings.add("Empty chunks: " + emptyChunkCount);
        }
        if (missingCitationMetadata > 0) {
            warnings.add("Child chunks missing citation metadata: " + missingCitationMetadata);
        }

        return new IngestionQualityDto.DocumentQualityDto(
                document.getId(),
                document.getTitle(),
                document.getFileName(),
                document.isActive(),
                chunkCount,
                embeddedChunkCount,
                childMissingEmbeddingCount,
                parentChunkCount,
                childChunkCount,
                orphanChildChunkCount,
                emptyChunkCount,
                missingCitationMetadata,
                List.copyOf(warnings));
    }

    private static List<String> brainWarnings(List<IngestionQualityDto.DocumentQualityDto> documents,
                                              int documentCount,
                                              int chunkCount,
                                              int childMissingEmbeddingCount,
                                              int orphanChildChunkCount,
                                              int emptyChunkCount,
                                              int duplicateChunkTextGroups,
                                              int missingCitationMetadata) {
        List<String> warnings = new ArrayList<>();
        if (documentCount == 0) {
            warnings.add("No documents indexed");
        }
        long documentsWithoutChunks = documents.stream()
                .filter(document -> document.chunkCount() == 0)
                .count();
        if (documentsWithoutChunks > 0) {
            warnings.add("Documents without chunks: " + documentsWithoutChunks);
        }
        if (chunkCount == 0 && documentCount > 0) {
            warnings.add("No chunks indexed");
        }
        if (childMissingEmbeddingCount > 0) {
            warnings.add("Child chunks missing embeddings: " + childMissingEmbeddingCount);
        }
        if (orphanChildChunkCount > 0) {
            warnings.add("Orphan child chunks: " + orphanChildChunkCount);
        }
        if (emptyChunkCount > 0) {
            warnings.add("Empty chunks: " + emptyChunkCount);
        }
        if (duplicateChunkTextGroups > 0) {
            warnings.add("Duplicate chunk text groups: " + duplicateChunkTextGroups);
        }
        if (missingCitationMetadata > 0) {
            warnings.add("Child chunks missing citation metadata: " + missingCitationMetadata);
        }
        return List.copyOf(warnings);
    }

    private static boolean belongsTo(DocumentChunk chunk, BrainDocument document) {
        if (chunk.getDocument() == null) {
            return false;
        }
        UUID documentId = document.getId();
        UUID chunkDocumentId = chunk.getDocument().getId();
        if (documentId != null && chunkDocumentId != null) {
            return documentId.equals(chunkDocumentId);
        }
        return chunk.getDocument() == document;
    }

    private static boolean isParent(DocumentChunk chunk) {
        return "PARENT".equalsIgnoreCase(chunk.getChunkType());
    }

    private static boolean isChild(DocumentChunk chunk) {
        return !isParent(chunk);
    }

    private static boolean isOrphanChild(DocumentChunk chunk) {
        return isChild(chunk) && chunk.getParentChunk() == null;
    }

    private static boolean isEmpty(DocumentChunk chunk) {
        return chunk.getContent() == null || chunk.getContent().isBlank();
    }

    private static boolean hasEmbedding(DocumentChunk chunk) {
        return chunk.getEmbedding() != null && chunk.getEmbedding().length > 0;
    }

    private static boolean hasCitationMetadata(DocumentChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return hasText(metadata.get("section"))
                || hasText(metadata.get("heading"))
                || hasText(metadata.get("hierarchy_path"))
                || metadata.containsKey("page_number")
                || metadata.containsKey("pageNumber");
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static int duplicateTextGroupCount(List<DocumentChunk> chunks) {
        return (int) chunks.stream()
                .map(IngestionQualityService::normalizedContent)
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.groupingBy(value -> value, HashMap::new, Collectors.counting()))
                .values()
                .stream()
                .filter(count -> count > 1)
                .count();
    }

    private static String normalizedContent(DocumentChunk chunk) {
        if (chunk.getContent() == null) {
            return "";
        }
        return chunk.getContent()
                .toLowerCase(Locale.US)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int count(List<DocumentChunk> chunks, Predicate<DocumentChunk> predicate) {
        return (int) chunks.stream().filter(predicate).count();
    }
}
