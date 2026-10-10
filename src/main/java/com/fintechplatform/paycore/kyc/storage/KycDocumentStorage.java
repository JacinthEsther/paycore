package com.fintechplatform.paycore.kyc.storage;

public interface KycDocumentStorage {

    String store(String prefix, String extension, byte[] content);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
