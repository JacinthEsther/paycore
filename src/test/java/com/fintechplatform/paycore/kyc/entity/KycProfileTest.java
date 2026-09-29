package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycProfileTest {

    private static final UUID REVIEWER = UUID.randomUUID();

    private Customer customer;
    private KycProfile profile;

    @BeforeEach
    void setUp() {

        customer = Customer.create(
                "Esther",
                "Test",
                "esther@example.com",
                "+2348012345678"
        );

        profile = KycProfile.create(customer);
    }

    @Test
    void shouldCreateNotStartedProfile() {

        assertThat(profile.getCustomer()).isSameAs(customer);
        assertThat(profile.getStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(profile.getCreatedAt()).isEqualTo(profile.getUpdatedAt());
        assertThat(profile.isVerified()).isFalse();
        assertThat(profile.getReviewReason()).isNull();
        assertThat(profile.getReviewedBy()).isNull();
    }

    @Test
    void shouldStartKyc() {

        profile.start();

        assertThat(profile.getStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        assertThat(profile.isInProgress()).isTrue();
    }

    @Test
    void shouldSubmitKyc() {

        profile.start();
        profile.submit();

        assertThat(profile.getStatus()).isEqualTo(KycStatus.SUBMITTED);
    }

    @Test
    void shouldMoveSubmittedKycToReview() {

        underReview();

        assertThat(profile.getStatus()).isEqualTo(KycStatus.UNDER_REVIEW);
    }

    @Test
    void shouldApproveKyc() {

        underReview();
        profile.approve(REVIEWER);

        assertThat(profile.getStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(profile.isVerified()).isTrue();
        assertThat(profile.getReviewedBy()).isEqualTo(REVIEWER);
        assertThat(profile.getReviewedAt()).isNotNull();
        assertThat(profile.getReviewReason()).isNull();
    }

    @Test
    void shouldRejectKycWithReason() {

        underReview();
        profile.reject(REVIEWER, "Identity document could not be validated");

        assertThat(profile.getStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(profile.getReviewReason())
                .isEqualTo("Identity document could not be validated");
        assertThat(profile.getReviewedBy()).isEqualTo(REVIEWER);
    }

    @Test
    void shouldRequestAdditionalInformationAndRestart() {

        underReview();
        profile.requestAdditionalInformation(
                REVIEWER,
                "Please provide a clearer proof of address"
        );

        assertThat(profile.getStatus())
                .isEqualTo(KycStatus.ADDITIONAL_INFO_REQUIRED);
        assertThat(profile.getReviewReason())
                .isEqualTo("Please provide a clearer proof of address");

        profile.start();

        assertThat(profile.getStatus()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldGoThroughReviewAgainAfterAdditionalInformation() {

        underReview();
        profile.requestAdditionalInformation(REVIEWER, "More please");

        profile.start();
        profile.submit();
        profile.startReview();
        profile.approve(REVIEWER);

        assertThat(profile.getStatus()).isEqualTo(KycStatus.VERIFIED);
        // The earlier request's reason no longer applies.
        assertThat(profile.getReviewReason()).isNull();
    }

    @Test
    void shouldNotStartKycTwice() {

        profile.start();

        assertThatThrownBy(profile::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KYC cannot be started from status IN_PROGRESS");
    }

    @Test
    void shouldNotApproveKycBeforeReview() {

        assertThatThrownBy(() -> profile.approve(REVIEWER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_STARTED");
    }

    @Test
    void shouldNotSubmitKycThatWasNotStarted() {

        assertThatThrownBy(profile::submit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldNotSubmitWhileAdditionalInformationIsOutstanding() {

        underReview();
        profile.requestAdditionalInformation(REVIEWER, "More please");

        assertThatThrownBy(profile::submit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "KYC cannot be submitted from status ADDITIONAL_INFO_REQUIRED"
                );
    }

    @Test
    void shouldNotStartReviewBeforeSubmission() {

        profile.start();

        assertThatThrownBy(profile::startReview)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KYC cannot enter review from status IN_PROGRESS");
    }

    @Test
    void shouldNotRejectSubmittedKycBeforeReview() {

        profile.start();
        profile.submit();

        assertThatThrownBy(() -> profile.reject(REVIEWER, "no"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldNotRequestInformationBeforeReview() {

        profile.start();
        profile.submit();

        assertThatThrownBy(() ->
                profile.requestAdditionalInformation(REVIEWER, "More please")
        )
                .isInstanceOf(IllegalStateException.class);

        assertThat(profile.getReviewReason()).isNull();
    }

    @Test
    void shouldNotReopenVerifiedKyc() {

        underReview();
        profile.approve(REVIEWER);

        assertThatThrownBy(profile::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KYC cannot be started from status VERIFIED");

        assertThat(profile.getStatus()).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void shouldNotReopenRejectedKyc() {

        underReview();
        profile.reject(REVIEWER, "no");

        assertThatThrownBy(profile::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldNotRestartWhileUnderReview() {

        underReview();

        assertThatThrownBy(profile::start)
                .isInstanceOf(IllegalStateException.class);

        assertThat(profile.isInProgress()).isFalse();
    }

    @Test
    void shouldTouchUpdatedAtOnTransition() {

        profile.start();

        assertThat(profile.getUpdatedAt())
                .isAfterOrEqualTo(profile.getCreatedAt());
    }

    @Test
    void shouldRecordWhoResetBvnAttemptsAndWhen() {

        UUID adminId = UUID.randomUUID();
        Instant before = Instant.now();

        profile.start();
        profile.resetBvnAttempts(adminId);

        assertThat(profile.getBvnAttemptsResetBy()).isEqualTo(adminId);
        assertThat(profile.getBvnAttemptsResetAt()).isAfterOrEqualTo(before);
        assertThat(profile.getStatus()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldAllowBvnAttemptResetWhileCustomerCanStillVerify() {

        profile.resetBvnAttempts(UUID.randomUUID());

        underReview();
        profile.requestAdditionalInformation(REVIEWER, "More please");

        profile.resetBvnAttempts(UUID.randomUUID());

        assertThat(profile.getBvnAttemptsResetAt()).isNotNull();
    }

    @Test
    void shouldNotResetBvnAttemptsOnceUnderReviewOrDecided() {

        underReview();

        assertThatThrownBy(() -> profile.resetBvnAttempts(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BVN attempts cannot be reset from status UNDER_REVIEW");

        profile.approve(REVIEWER);

        assertThatThrownBy(() -> profile.resetBvnAttempts(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(profile.getBvnAttemptsResetAt()).isNull();
    }

    private void underReview() {

        profile.start();
        profile.submit();
        profile.startReview();
    }
}
