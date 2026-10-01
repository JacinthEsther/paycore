package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.notification.EmailSentEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the emails PayCore sent so Developer Preview visitors, who sign up
 * with made-up addresses, can still open their verification link. Each
 * signed-in visitor only ever sees mail addressed to their own email.
 *
 * In memory and bounded: the latest few emails for the most recent
 * addresses. A restart empties it; the visitor then asks for a new link.
 */
@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoOutbox {

    private static final int MAX_ADDRESSES = 1_000;
    private static final int MAX_EMAILS_PER_ADDRESS = 5;

    public record DemoEmail(String to, String subject, String body, Instant sentAt) {
    }

    /** Least recently written address first, evicted beyond the limit. */
    private final Map<String, Deque<DemoEmail>> byAddress =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<DemoEmail>> eldest) {
                    return size() > MAX_ADDRESSES;
                }
            };

    @EventListener
    public synchronized void onEmailSent(EmailSentEvent event) {

        Deque<DemoEmail> emails =
                byAddress.computeIfAbsent(key(event.email().to()), address -> new ArrayDeque<>());

        emails.addFirst(new DemoEmail(
                event.email().to(),
                event.email().subject(),
                event.email().body(),
                Instant.now()
        ));

        while (emails.size() > MAX_EMAILS_PER_ADDRESS) {
            emails.removeLast();
        }
    }

    /** Newest first. */
    public synchronized List<DemoEmail> emailsTo(String address) {
        return new ArrayList<>(byAddress.getOrDefault(key(address), new ArrayDeque<>()));
    }

    private static String key(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
