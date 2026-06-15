package com.msfg.rag.controller;

import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.BrainResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Brain identity + corpus counts for the dashboard shell and corpus screen. */
@RestController
@RequestMapping("/api/ai/admin/stats")
public class AdminStatsController {

    public record BrainDto(String companyName, String slug) {}
    public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
    public record StatsDto(BrainDto brain, CorpusDto corpus) {}

    private final DomainPackRegistry packRegistry;
    private final BrainResolver brainResolver;
    private final MortgageDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public AdminStatsController(DomainPackRegistry packRegistry,
                                BrainResolver brainResolver,
                                MortgageDocumentRepository documents,
                                DocumentChunkRepository chunks) {
        this.packRegistry = packRegistry;
        this.brainResolver = brainResolver;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public StatsDto stats(@RequestParam(value = "brain", required = false) String brain) {
        var resolved = brainResolver.resolve(brain);
        var pack = packRegistry.bundle(resolved.getId()).pack();
        UUID brainId = resolved.getId();
        return new StatsDto(
                new BrainDto(pack.companyName(), pack.slug()),
                new CorpusDto(documents.countByBrainIdAndActiveTrue(brainId),
                              documents.countByBrainId(brainId),
                              chunks.countByBrainId(brainId)));
    }
}
