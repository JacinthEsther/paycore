package com.fintechplatform.paycore.common.persistence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    @Test
    void shouldGenerateVersion7Uuid() {

        UUID uuid = generate();

        System.out.println("======================================");
        System.out.println("Generated UUID : " + uuid);
        System.out.println("UUID Version   : " + uuid.version());
        System.out.println("UUID Variant   : " + uuid.variant());
        System.out.println("======================================");

        assertThat(uuid.version())
                .isEqualTo(7);

        assertThat(uuid.variant())
                .isEqualTo(2);
    }

    @Test
    void shouldGenerateTimeOrderedUuids() {

        List<UUID> uuids = new ArrayList<>();

        for (int i = 0; i < 1_000; i++) {
            uuids.add(generate());
        }

        System.out.println("First UUID : " + uuids.getFirst());
        System.out.println("Last UUID  : " + uuids.getLast());

        System.out.println("\nFirst 10 generated UUIDs:");

        for (int i = 0; i < Math.min(10, uuids.size()); i++) {
            System.out.println(
                    (i + 1) + " -> " + uuids.get(i)
            );
        }

        assertThat(uuids)
                .isSortedAccordingTo(UUID::compareTo);

        assertThat(new HashSet<>(uuids))
                .hasSize(uuids.size());
    }

    @Test
    void shouldEmbedCurrentUnixTimestamp() {

        long before = System.currentTimeMillis();

        UUID uuid = generate();

        long after = System.currentTimeMillis();

        long timestamp =
                uuid.getMostSignificantBits() >>> 16;

        System.out.println("======================================");
        System.out.println("UUID              : " + uuid);
        System.out.println("Extracted time    : " + timestamp);
        System.out.println("Before generation : " + before);
        System.out.println("After generation  : " + after);
        System.out.println("UUID version      : " + uuid.version());
        System.out.println("UUID variant      : " + uuid.variant());
        System.out.println("======================================");

        assertThat(timestamp)
                .isBetween(before, after);
    }

    private UUID generate() {
        return (UUID) generator.generate(null, null, null, null);
    }
}