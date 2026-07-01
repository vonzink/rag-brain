package com.ragbrain.rag.config;

import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(DefaultBrainSeeder.class)
@TestPropertySource(properties = {
        "brain.slug=mortgage",
        "brain.pack=packs/msfg-mortgage",
        "brain.corpus.bucket=msfg.us",
        "brain.corpus.prefix=rag-brain/",
        "brain.corpus.region=us-west-1",
        "ragbrain.rag.routing.default-provider=anthropic",
        "spring.ai.anthropic.chat.options.model=claude-haiku-4-5",
        "ragbrain.rag.routing.fallback-provider=openai",
        "spring.ai.openai.chat.options.model=gpt-4.1-nano"
})
class DefaultBrainSeederTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Autowired
    private DefaultBrainSeeder seeder;

    @Test
    void reconcilesDefaultBrainFromConfig() {
        seeder.run(null);

        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals("mortgage", def.getSlug());
        assertEquals("packs/msfg-mortgage", def.getPackRef());
        assertEquals("s3", def.getSourceType());
        assertEquals("msfg.us", def.getS3Bucket());
        assertEquals("rag-brain/", def.getS3Prefix());
        assertEquals("us-west-1", def.getS3Region());
        assertEquals("anthropic", def.getAnswerProvider());
        assertEquals("claude-haiku-4-5", def.getAnswerModel());
        assertEquals("openai", def.getUtilityProvider());
        assertEquals("gpt-4.1-nano", def.getUtilityModel());
    }

    @Test
    void isIdempotent() {
        seeder.run(null);
        seeder.run(null);
        assertEquals(1, brains.count());
        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals("mortgage", def.getSlug());
        assertEquals("s3", def.getSourceType());
        assertEquals("msfg.us", def.getS3Bucket());
        assertEquals("anthropic", def.getAnswerProvider());
        assertEquals("claude-haiku-4-5", def.getAnswerModel());
        assertEquals("openai", def.getUtilityProvider());
        assertEquals("gpt-4.1-nano", def.getUtilityModel());
    }
}
