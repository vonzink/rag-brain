package com.msfg.rag.service.sync;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceTrustLevel;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In-app port of scripts/s3-ingest/sync.mjs: list corpus + manifest, hash,
 * plan, execute through the ingestion pipeline. Per-file failures are
 * collected, never abort the batch, and an UPDATE deactivates the previous
 * version only after the replacement ingested successfully (spec §10).
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final CorpusSourceFactory corpusSourceFactory;
    private final BrainRepository brainRepository;
    private final DocumentIngestionService ingestionService;
    private final MortgageDocumentRepository documentRepository;

    public SyncService(CorpusSourceFactory corpusSourceFactory,
                       BrainRepository brainRepository,
                       DocumentIngestionService ingestionService,
                       MortgageDocumentRepository documentRepository) {
        this.corpusSourceFactory = corpusSourceFactory;
        this.brainRepository = brainRepository;
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
    }

    public SyncReport sync(boolean dryRun, UUID brainId) {
        Brain brain = brainRepository.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
        CorpusSource corpusSource = corpusSourceFactory.forBrain(brain);

        SyncManifest manifest = SyncManifest.parse(corpusSource.fetchManifest());
        List<String> s3Files = corpusSource.listFiles();
        List<MortgageDocument> brainDocs = documentRepository.findByBrainId(brainId);

        // Fetch once; reuse bytes for hashing and (later) ingestion.
        Map<String, byte[]> bytesByFile = new HashMap<>();
        Map<String, String> hashes = new HashMap<>();
        for (String fileName : s3Files) {
            byte[] bytes = corpusSource.fetch(fileName);
            bytesByFile.put(fileName, bytes);
            hashes.put(fileName, Sha256.hex(bytes));
        }

        List<SyncAction> plan = SyncPlanner.plan(s3Files, manifest, brainDocs, hashes);

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (SyncAction action : plan) {
            summary.merge(action.type().name().toLowerCase(Locale.US), 1, Integer::sum);
        }

        List<SyncReport.Result> results = new ArrayList<>(plan.size());
        for (SyncAction action : plan) {
            if (dryRun || action.type() == SyncAction.Type.SKIP) {
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), false, true, null));
                continue;
            }
            try {
                execute(action, bytesByFile, brainId);
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), true, true, null));
            } catch (Exception e) {
                log.warn("Sync {} failed for {}: {}", action.type(), action.fileName(), e.getMessage());
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), true, false, e.getMessage()));
            }
        }
        return new SyncReport(dryRun, summary, results);
    }

    private void execute(SyncAction action, Map<String, byte[]> bytesByFile, UUID brainId) {
        switch (action.type()) {
            case UPLOAD -> ingest(action, bytesByFile.get(action.fileName()), brainId);
            case UPDATE -> {
                // New version first; old rows stay active until success. Then
                // deactivate every stale active row with this fileName — covers
                // the planned row AND any pre-existing duplicate actives.
                MortgageDocument replacement = ingest(action, bytesByFile.get(action.fileName()), brainId);
                for (MortgageDocument stale : documentRepository.findByBrainIdAndActiveTrue(brainId)) {
                    if (stale.getFileName().equals(action.fileName())) {
                        boolean same = stale == replacement
                                || (stale.getId() != null && stale.getId().equals(replacement.getId()));
                        if (!same) {
                            stale.setActive(false);
                            documentRepository.save(stale);
                        }
                    }
                }
            }
            case REACTIVATE -> setActive(action.documentId(), true);
            case DEACTIVATE -> setActive(action.documentId(), false);
            case SKIP -> { /* never reaches here */ }
        }
    }

    private MortgageDocument ingest(SyncAction action, byte[] bytes, UUID brainId) {
        SyncManifest.Entry meta = action.meta();
        return ingestionService.ingest(
                action.fileName(),
                bytes,
                meta.title(),
                meta.sourceName(),
                SourceType.valueOf(meta.sourceType()),
                SourceVisibility.valueOf(meta.visibility()),
                SourceTrustLevel.valueOf(meta.trustLevel()),
                meta.documentVersion(),
                meta.effectiveDate() == null ? null : LocalDate.parse(meta.effectiveDate()),
                meta.expirationDate() == null ? null : LocalDate.parse(meta.expirationDate()),
                brainId);
    }

    private void setActive(java.util.UUID documentId, boolean active) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        document.setActive(active);
        documentRepository.save(document);
    }
}
