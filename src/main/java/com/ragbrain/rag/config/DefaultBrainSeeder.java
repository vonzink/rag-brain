package com.ragbrain.rag.config;

import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the default brain (seeded by V7) to this deployment's live config
 * at boot, so its slug/pack/source/model reflect the actual env. Idempotent:
 * it updates the single default brain in place, never creating extra rows.
 */
@Component
@Order(0)
public class DefaultBrainSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultBrainSeeder.class);

    private final BrainRepository brains;
    private final String slug;
    private final String displayName;
    private final String packRef;
    private final String s3Bucket;
    private final String s3Prefix;
    private final String s3Region;
    private final String localPath;
    private final String answerProvider;
    private final String answerModel;
    private final String utilityProvider;
    private final String utilityModel;

    public DefaultBrainSeeder(
            BrainRepository brains,
            @Value("${brain.slug:generic}") String slug,
            @Value("${brain.name:Generic Brain}") String displayName,
            @Value("${brain.pack:packs/generic}") String packRef,
            @Value("${brain.corpus.bucket:}") String s3Bucket,
            @Value("${brain.corpus.prefix:}") String s3Prefix,
            @Value("${brain.corpus.region:}") String s3Region,
            @Value("${ragbrain.rag.storage.path:}") String localPath,
            @Value("${ragbrain.rag.routing.default-provider:anthropic}") String answerProvider,
            @Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5}") String answerModel,
            @Value("${ragbrain.rag.routing.fallback-provider:openai}") String utilityProvider,
            @Value("${spring.ai.openai.chat.options.model:gpt-4.1-nano}") String utilityModel) {
        this.brains = brains;
        this.slug = slug;
        this.displayName = displayName;
        this.packRef = packRef;
        this.s3Bucket = s3Bucket;
        this.s3Prefix = s3Prefix;
        this.s3Region = s3Region;
        this.localPath = localPath;
        this.answerProvider = answerProvider;
        this.answerModel = answerModel;
        this.utilityProvider = utilityProvider;
        this.utilityModel = utilityModel;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Brain brain = brains.findDefaultBrain().orElseThrow(() ->
                new IllegalStateException("No default brain present — V7 migration must seed one"));

        brain.setSlug(slug);
        brain.setDisplayName(displayName);
        brain.setPackRef(packRef);
        if (!s3Bucket.isBlank()) {
            brain.setSourceType("s3");
            brain.setS3Bucket(s3Bucket);
            brain.setS3Prefix(s3Prefix);
            brain.setS3Region(s3Region);
            brain.setLocalPath(null);
        } else if (!localPath.isBlank()) {
            brain.setSourceType("local");
            brain.setS3Bucket(null);
            brain.setS3Prefix(null);
            brain.setS3Region(null);
            brain.setLocalPath(localPath);
        } else {
            brain.setSourceType(null);
            brain.setS3Bucket(null);
            brain.setS3Prefix(null);
            brain.setS3Region(null);
            brain.setLocalPath(null);
            log.warn("No corpus source configured (s3 bucket and local path both blank); sourceType left unset");
        }
        brain.setAnswerProvider(answerProvider);
        brain.setAnswerModel(answerModel);
        brain.setUtilityProvider(utilityProvider);
        brain.setUtilityModel(utilityModel);
        brains.save(brain);

        log.info("Default brain reconciled from config: slug='{}', pack='{}', source={}",
                brain.getSlug(), brain.getPackRef(), brain.getSourceType());
    }
}
