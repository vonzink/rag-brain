package com.msfg.rag.pack;

import java.nio.file.Path;

/** Loads the real MSFG pack for tests (working dir = repo root under Gradle). */
public final class TestPacks {

    private static DomainPack msfg;

    private TestPacks() {}

    /** Lazy + memoized: a broken pack fails each test with the loader's own message. */
    public static synchronized DomainPack msfg() {
        if (msfg == null) {
            msfg = new DomainPackLoader().load(Path.of("packs/msfg-mortgage"));
        }
        return msfg;
    }

    /** A registry preloaded with one brain id → bundle, for consumer unit tests. */
    public static DomainPackRegistry registryFor(java.util.UUID brainId, DomainPack pack) {
        DomainPackRegistry registry =
                new DomainPackRegistry(org.mockito.Mockito.mock(
                        com.msfg.rag.repository.BrainRepository.class));
        registry.preload(brainId, BrainPackBundle.of(pack));
        return registry;
    }

    /** A registry preloaded with several brain ids → packs. */
    public static DomainPackRegistry registryFor(java.util.Map<java.util.UUID, DomainPack> packs) {
        DomainPackRegistry registry = new DomainPackRegistry(
                org.mockito.Mockito.mock(com.msfg.rag.repository.BrainRepository.class));
        packs.forEach((id, p) -> registry.preload(id, BrainPackBundle.of(p)));
        return registry;
    }

    /** The default-brain registry (DEFAULT_ID → the real MSFG pack). */
    public static DomainPackRegistry registry() {
        return registryFor(com.msfg.rag.TestBrains.DEFAULT_ID, msfg());
    }
}
