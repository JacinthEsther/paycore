package com.fintechplatform.paycore.kyc.dto;

import org.springframework.data.domain.Page;

/**
 * Paging metadata for a list response. {@code number} is zero-based.
 */
public record PageInfo(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static PageInfo of(Page<?> page) {
        return new PageInfo(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
