package com.bank.compliance.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceContextOpenApiContractTest {

    @Test
    void shouldDefineImplementedComplianceEndpoints() throws IOException {
        String spec = loadSpec();

        assertThat(spec).doesNotContain("paths: {}");
        assertThat(spec).contains("\n  /api/v1/compliance/screen:\n");
        assertThat(spec).contains("\n  /api/v1/compliance/screenings/{transactionId}:\n");
    }

    /**
     * Platform contract addendum 2026-10-08: DPoP binding applies only to
     * open-finance TPP clients. This service's callers are internal services
     * and staff with Bearer tokens, and it does not verify DPoP, so the spec
     * must not promise a DPoP header is required.
     */
    @Test
    @SuppressWarnings("unchecked")
    void dpopHeaderIsDocumentedButNotRequired() throws IOException {
        Map<String, Object> spec = new Yaml().load(loadSpec());
        Map<String, Object> parameters = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("parameters");
        Map<String, Object> dpop = (Map<String, Object>) parameters.get("DPoP");

        assertThat(dpop).containsEntry("name", "DPoP").containsEntry("in", "header").containsEntry("required", false);
        assertThat((String) dpop.get("description")).contains("open-finance TPP").contains("not verified");
        assertThat((Map<String, Object>) parameters.get("Authorization")).containsEntry("required", true);
    }

    private static String loadSpec() throws IOException {
        List<Path> candidates = List.of(
                Path.of("api/openapi/compliance-context.yaml"),
                Path.of("../api/openapi/compliance-context.yaml"),
                Path.of("../../api/openapi/compliance-context.yaml"),
                Path.of("../../../api/openapi/compliance-context.yaml")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Unable to locate compliance-context.yaml");
    }
}
