package com.fintechplatform.paycore.account.repository;

import com.fintechplatform.paycore.account.entity.AccountStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountStatusEventRepository
        extends JpaRepository<AccountStatusEvent, UUID> {

    List<AccountStatusEvent> findByAccountIdOrderByOccurredAtAscIdAsc(UUID accountId);
}
