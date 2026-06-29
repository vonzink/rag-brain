package com.msfg.rag.service.ingestion;

import com.msfg.rag.domain.DocumentChunk;
import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceTrustLevel;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.service.sync.Sha256;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full ingestion pipeline: store file -> extract text -> chunk -> embed -> persist.
 * This is what turns an uploaded guideline PDF into searchable knowledge.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** OpenAI embedding batch limit is generous; 64 keeps request sizes sane. */
    private static final int EMBEDDING_BATCH_SIZE = 64;

    private final StorageService storageService;
    private final TextExtractionService textExtractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MortgageDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    /**
     * Explicit transaction boundaries (instead of method-level @Transactional) so
     * the expensive text-extraction + embedding work runs OUTSIDE any transaction
     * and only the short DB writes are transactional — no JDBC connection is held
     * across the embedding API calls (which can sleep on rate-limit backoff).
     */
    private final TransactionTemplate txTemplate;

    public DocumentIngestionService(StorageService storageService,
                                    TextExtractionService textExtractionService,
                                    ChunkingService chunkingService,
                                    EmbeddingService embeddingService,
                                    MortgageDocumentRepository documentRepository,
                                    DocumentChunkRepository chunkRepository,
                                    PlatformTransactionManager transactionManager) {
        this.storageService = storageService;
        this.textExtractionService = textExtractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Ingests a new guideline document end to end. The blob is stored first, then
     * text extraction/chunking/embedding runs with no open transaction, and the
     * document + chunks are committed together in one short transaction. If
     * anything fails, the stored blob is removed so no orphan file is left behind.
     */
    public MortgageDocument ingest(String fileName,
                                   byte[] fileBytes,
                                   String title,
                                   String sourceName,
                                   SourceType sourceType,
                                   SourceVisibility visibility,
                                   SourceTrustLevel trustLevel,
                                   String documentVersion,
                                   LocalDate effectiveDate,
                                   LocalDate expirationDate,
                                   UUID brainId) {

        String storageKey = storageService.store(fileName, new ByteArrayInputStream(fileBytes));
        try {
            MortgageDocument document = new MortgageDocument();
            document.setBrainId(brainId);
            document.setTitle(title);
            document.setSourceName(sourceName);
            document.setSourceType(sourceType);
            document.setVisibility(visibility == null ? SourceVisibility.INTERNAL : visibility);
            document.setTrustLevel(trustLevel == null ? SourceTrustLevel.APPROVED : trustLevel);
            document.setFileName(fileName);
            document.setS3Key(storageKey);
            document.setDocumentVersion(documentVersion);
            document.setContentSha256(Sha256.hex(fileBytes));
            document.setEffectiveDate(effectiveDate);
            document.setExpirationDate(expirationDate);

            // Extract + chunk + embed OUTSIDE any transaction.
            PreparedChunks prepared = prepareChunks(document, new ByteArrayInputStream(fileBytes));

            MortgageDocument saved = txTemplate.execute(status -> {
                MortgageDocument persisted = documentRepository.save(document);
                int chunkCount = persistChunks(persisted, prepared);
                log.info("Ingested document '{}' ({}): {} chunks", title, fileName, chunkCount);
                return persisted;
            });
            return saved;
        } catch (RuntimeException e) {
            // Compensate: a committed document row must never outlive its blob, and
            // a failed ingest must not leak the blob it just stored.
            safeDeleteStorage(storageKey);
            throw e;
        }
    }

    /**
     * Re-runs extraction, chunking, and embedding for an existing document,
     * e.g. after chunking settings change or an embedding model upgrade.
     * Embedding runs first (no transaction); the old chunks are swapped for the
     * new ones in a single transaction, so a failure mid-reindex leaves the
     * existing chunks intact rather than emptying the document.
     */
    public int reindex(UUID documentId) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        PreparedChunks prepared;
        try (InputStream content = storageService.retrieve(document.getS3Key())) {
            prepared = prepareChunks(document, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored document for reindex: " + documentId, e);
        }

        int chunkCount = txTemplate.execute(status -> {
            chunkRepository.deleteByDocumentId(documentId);
            return persistChunks(document, prepared);
        });
        log.info("Reindexed document '{}': {} chunks", document.getTitle(), chunkCount);
        return chunkCount;
    }

    public void delete(UUID documentId) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        String storageKey = document.getS3Key();

        // Delete the DB rows first and commit, THEN remove the blob. Deleting the
        // blob before the commit would risk a surviving row pointing at a missing
        // file if the transaction rolled back.
        txTemplate.executeWithoutResult(status -> {
            chunkRepository.deleteByDocumentId(documentId);
            documentRepository.delete(document);
        });
        safeDeleteStorage(storageKey);
        log.info("Deleted document '{}' ({})", document.getTitle(), document.getFileName());
    }

    /** Text extraction, chunking, and embedding — performed with no open transaction. */
    private PreparedChunks prepareChunks(MortgageDocument document, InputStream content) {
        String text = textExtractionService.extract(content, document.getFileName());
        if (text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from " + document.getFileName()
                    + " — is it a scanned image PDF? OCR is not supported yet.");
        }

        List<TextChunk> textChunks = chunkingService.chunkHierarchical(text);
        List<TextChunk> childChunks = textChunks.stream()
                .filter(tc -> tc.type() == TextChunk.ChunkType.CHILD)
                .toList();

        Map<Integer, float[]> embeddingByChildIndex = new HashMap<>();
        // Embed in batches to limit API request size.
        for (int start = 0; start < childChunks.size(); start += EMBEDDING_BATCH_SIZE) {
            List<TextChunk> batch = childChunks.subList(
                    start, Math.min(start + EMBEDDING_BATCH_SIZE, childChunks.size()));
            List<float[]> embeddings = embeddingService.embedBatch(
                    batch.stream().map(TextChunk::content).toList());
            if (embeddings.size() != batch.size()) {
                throw new IllegalStateException("Embedding provider returned " + embeddings.size()
                        + " vectors for " + batch.size() + " chunks");
            }
            for (int i = 0; i < batch.size(); i++) {
                embeddingByChildIndex.put(batch.get(i).index(), embeddings.get(i));
            }
        }
        return new PreparedChunks(textChunks, embeddingByChildIndex);
    }

    /** Persists parent + child chunks. MUST run inside a transaction. */
    private int persistChunks(MortgageDocument document, PreparedChunks prepared) {
        Map<Integer, DocumentChunk> parentByIndex = new HashMap<>();
        for (TextChunk tc : prepared.textChunks()) {
            if (tc.type() != TextChunk.ChunkType.PARENT) {
                continue;
            }
            parentByIndex.put(tc.index(), chunkRepository.save(toEntity(document, tc, null, null)));
        }

        List<DocumentChunk> children = new ArrayList<>();
        for (TextChunk tc : prepared.textChunks()) {
            if (tc.type() != TextChunk.ChunkType.CHILD) {
                continue;
            }
            DocumentChunk parent = tc.parentIndex() == null ? null : parentByIndex.get(tc.parentIndex());
            children.add(toEntity(document, tc, prepared.childEmbeddings().get(tc.index()), parent));
        }
        chunkRepository.saveAll(children);
        return parentByIndex.size() + children.size();
    }

    private void safeDeleteStorage(String storageKey) {
        if (storageKey == null) {
            return;
        }
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete stored file '{}' during cleanup: {}", storageKey, ex.getMessage());
        }
    }

    /** Text chunks plus the embedding for each child chunk, keyed by chunk index. */
    private record PreparedChunks(List<TextChunk> textChunks, Map<Integer, float[]> childEmbeddings) {
    }

    private DocumentChunk toEntity(MortgageDocument document, TextChunk tc, float[] embedding,
                                   DocumentChunk parent) {
        DocumentChunk entity = new DocumentChunk();
        entity.setBrainId(document.getBrainId());
        entity.setDocument(document);
        entity.setChunkIndex(tc.index());
        entity.setChunkType(tc.type().name());
        entity.setParentChunk(parent);
        entity.setHierarchyPath(tc.path());
        entity.setHierarchyLevel(tc.level());
        entity.setContent(tc.content());
        entity.setTokenCount(tc.tokenCount());
        entity.setEmbedding(embedding);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_name", document.getSourceName());
        metadata.put("source_type", document.getSourceType().name());
        metadata.put("visibility", document.getVisibility().name());
        metadata.put("trust_level", document.getTrustLevel().name());
        metadata.put("document_name", document.getFileName());
        metadata.put("chunk_type", tc.type().name());
        if (tc.parentIndex() != null) {
            metadata.put("parent_chunk_index", tc.parentIndex());
        }
        if (tc.path() != null) {
            metadata.put("hierarchy_path", tc.path());
        }
        if (tc.heading() != null) {
            metadata.put("section", tc.heading());
        }
        if (document.getEffectiveDate() != null) {
            metadata.put("effective_date", document.getEffectiveDate().toString());
        }
        if (document.getDocumentVersion() != null) {
            metadata.put("version", document.getDocumentVersion());
        }
        entity.setMetadata(metadata);
        return entity;
    }
}
