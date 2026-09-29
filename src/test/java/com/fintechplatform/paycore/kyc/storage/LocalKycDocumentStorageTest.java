package com.fintechplatform.paycore.kyc.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalKycDocumentStorageTest {

    @TempDir
    Path root;

    @Test
    void shouldStoreFileUnderGeneratedName() throws IOException {

        LocalKycDocumentStorage storage =
                new LocalKycDocumentStorage(root.toString());

        byte[] content = {1, 2, 3};

        String key = storage.store("profile-1", "pdf", content);

        assertThat(key).matches("profile-1/[0-9a-f-]{36}\\.pdf");
        assertThat(Files.readAllBytes(root.resolve(key))).isEqualTo(content);
    }

    @Test
    void shouldGiveEveryUploadItsOwnKey() {

        LocalKycDocumentStorage storage =
                new LocalKycDocumentStorage(root.toString());

        assertThat(storage.store("p", "png", new byte[]{1}))
                .isNotEqualTo(storage.store("p", "png", new byte[]{1}));
    }

    @Test
    void shouldDeleteStoredFileAndIgnoreMissingOnes() {

        LocalKycDocumentStorage storage =
                new LocalKycDocumentStorage(root.toString());

        String key = storage.store("p", "jpg", new byte[]{1});

        storage.delete(key);
        storage.delete(key);

        assertThat(root.resolve(key)).doesNotExist();
    }

    @Test
    void shouldRefusePathsOutsideTheRoot() {

        LocalKycDocumentStorage storage =
                new LocalKycDocumentStorage(root.resolve("docs").toString());

        assertThatThrownBy(() -> storage.store("../escape", "pdf", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> storage.delete("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(root.resolve("escape")).doesNotExist();
    }
}
