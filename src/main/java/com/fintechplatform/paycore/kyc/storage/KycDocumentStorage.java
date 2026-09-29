package com.fintechplatform.paycore.kyc.storage;

/**
 * Where uploaded KYC document files live. The database keeps only the
 * returned storage key, so swapping this for S3 or Azure Blob does not
 * touch KYC business logic.
 */
public interface KycDocumentStorage {

    /**
     * Stores the file and returns the key it can later be found under.
     *
     * @param prefix    groups files, e.g. by KYC profile; must be a
     *                  server-generated value, never client input
     * @param extension file extension without the dot, e.g. "pdf"
     */
    String store(String prefix, String extension, byte[] content);

    /**
     * Removes a stored file. Missing files are ignored.
     */
    void delete(String storageKey);
}
