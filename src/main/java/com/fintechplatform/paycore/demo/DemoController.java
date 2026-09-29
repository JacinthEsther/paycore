package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.kyc.provider.KycProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the demo UI which shared admin account visitors can sign in with.
 * Publishing a password is deliberate here and only acceptable because
 * the endpoint exists solely in demo mode, on sandbox data. Without demo
 * mode the path does not exist.
 */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoController {

    private final DemoProperties properties;
    private final KycProvider kycProvider;

    public DemoController(
            DemoProperties properties,
            KycProvider kycProvider
    ) {
        this.properties = properties;
        this.kycProvider = kycProvider;
    }

    @GetMapping
    public DemoInfoResponse info() {

        return new DemoInfoResponse(
                true,
                properties.normalizedAdminEmail(),
                properties.getAdminPassword(),
                kycProvider.providerName()
        );
    }

    /**
     * @param kycProvider which identity provider answers BVN/NIN checks,
     *                    so the UI can say when results are simulated
     */
    public record DemoInfoResponse(
            boolean enabled,
            String adminEmail,
            String adminPassword,
            String kycProvider
    ) {
    }
}
