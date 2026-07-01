package com.ragbrain.rag.service.ingestion;

import com.ragbrain.rag.domain.BrainDocument;
import com.ragbrain.rag.domain.DocumentChunk;
import com.ragbrain.rag.domain.SourceType;
import com.ragbrain.rag.dto.IngestionQualityDto;
import com.ragbrain.rag.repository.BrainDocumentRepository;
import com.ragbrain.rag.repository.DocumentChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ragbrain.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionQualityServiceTest {

    private final BrainDocumentRepository documents = mock(BrainDocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final IngestionQualityService service = new IngestionQualityService(documents, chunks);

    @Test
    void evaluatesDocumentChunkEmbeddingHierarchyAndCitationHealth() {
        BrainDocument guideline = document("Guideline", true);
        BrainDocument empty = document("Empty", true);
        DocumentChunk parent = chunk(guideline, "PARENT", null, "PMI section", null,
                Map.of("section", "Overview"));
        DocumentChunk embeddedChild = chunk(guideline, "CHILD", parent, "PMI coverage rules",
                new float[] {0.1f, 0.2f}, Map.of("page_number", 4));
        DocumentChunk orphanMissingEmbedding = chunk(guideline, "CHILD", null, "PMI coverage rules",
                null, Map.of());
        DocumentChunk blankChild = chunk(guideline, "CHILD", parent, "   ",
                new float[] {0.3f}, Map.of("section", "Blank"));

        when(documents.findByBrainId(DEFAULT_ID)).thenReturn(List.of(guideline, empty));
        when(chunks.findByBrainId(DEFAULT_ID)).thenReturn(List.of(
                parent, embeddedChild, orphanMissingEmbedding, blankChild));

        IngestionQualityDto quality = service.evaluate(DEFAULT_ID);

        assertEquals(DEFAULT_ID, quality.brainId());
        assertEquals(2, quality.documentCount());
        assertEquals(2, quality.activeDocumentCount());
        assertEquals(4, quality.chunkCount());
        assertEquals(2, quality.embeddedChunkCount());
        assertEquals(1, quality.chunksMissingEmbeddingCount());
        assertEquals(1, quality.parentChunkCount());
        assertEquals(3, quality.childChunkCount());
        assertEquals(1, quality.orphanChildChunkCount());
        assertEquals(1, quality.emptyChunkCount());
        assertEquals(1, quality.duplicateChunkTextGroups());
        assertEquals(1, quality.chunksMissingCitationMetadata());
        assertEquals(2, quality.documents().size());
        assertTrue(quality.warnings().contains("Documents without chunks: 1"));
        assertTrue(quality.warnings().contains("Child chunks missing embeddings: 1"));
        assertTrue(quality.warnings().contains("Orphan child chunks: 1"));
        assertTrue(quality.warnings().contains("Duplicate chunk text groups: 1"));
        assertTrue(quality.documents().stream()
                .filter(doc -> doc.title().equals("Empty"))
                .findFirst()
                .orElseThrow()
                .warnings()
                .contains("Document has no chunks"));
    }

    private static BrainDocument document(String title, boolean active) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(DEFAULT_ID);
        document.setTitle(title);
        document.setSourceName("Source");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setFileName(title.toLowerCase() + ".pdf");
        document.setActive(active);
        return document;
    }

    private static DocumentChunk chunk(BrainDocument document,
                                       String type,
                                       DocumentChunk parent,
                                       String content,
                                       float[] embedding,
                                       Map<String, Object> metadata) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(DEFAULT_ID);
        chunk.setDocument(document);
        chunk.setChunkType(type);
        chunk.setParentChunk(parent);
        chunk.setContent(content);
        chunk.setTokenCount(content == null ? 0 : content.length());
        chunk.setEmbedding(embedding);
        chunk.setMetadata(metadata);
        return chunk;
    }
}
