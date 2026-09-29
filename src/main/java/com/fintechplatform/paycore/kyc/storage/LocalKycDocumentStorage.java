package com.fintechplatform.paycore.kyc.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Development storage on the local file system. File names are always
 * generated here; the client's original file name is never used, so it
 * cannot influence the path.
 */
@Component
public class LocalKycDocumentStorage implements KycDocumentStorage {

    private final Path root;

    public LocalKycDocumentStorage(
            @Value("${paycore.kyc.documents.storage-dir:data/kyc-documents}")
            String rootDirectory
    ) {
        this.root = Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String store(String prefix, String extension, byte[] content) {

        String storageKey =
                prefix + "/" + UUID.randomUUID() + "." + extension;

        Path target = resolve(storageKey);

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Unable to store KYC document",
                    exception
            );
        }

        return storageKey;
    }

    @Override
    public void delete(String storageKey) {

        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Unable to delete KYC document",
                    exception
            );
        }
    }

    private Path resolve(String storageKey) {

        Path target = root.resolve(storageKey).normalize();

        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid document path");
        }

        return target;
    }
}
