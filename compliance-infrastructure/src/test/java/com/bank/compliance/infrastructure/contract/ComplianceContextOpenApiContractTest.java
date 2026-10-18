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
        assertThat(spec).contains("\n  /api/v2/compliance/screen:\n");
        assertThat(spec).contains("\n  /api/v1/compliance/screenings/{transactionId}:\n");
    }

    /**
     * v2 (screening facts computed by their owners): the caller sends ids and
     * the amount only; v1 stays, deprecated, behind COMPLIANCE_V1_CALLER_FACTS_ENABLED
     * and answers 410 when the flag is off.
     */
    @Test
    @SuppressWarnings("unchecked")
    void v2TakesIdsAndAmountOnlyAndV1IsDeprecated() throws IOException {
        Map<String, Object> spec = new Yaml().load(loadSpec());
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> v1 = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/compliance/screen")).get("post");
        Map<String, Object> v2 = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v2/compliance/screen")).get("post");

        assertThat(v1).containsEntry("deprecated", true);
        assertThat((String) v1.get("description")).contains("COMPLIANCE_V1_CALLER_FACTS_ENABLED").contains("/api/v2/compliance/screen");
        assertThat(((Map<String, Object>) v1.get("responses")).keySet()).contains("410");
        assertThat(v2).doesNotContainKey("deprecated");
        assertThat(((Map<String, Object>) v2.get("responses")).keySet()).contains("201", "400", "401", "403", "409", "422", "500", "503");
        assertThat((String) ((Map<String, Object>) ((Map<String, Object>) v2.get("responses")).get("503")).get("description"))
                .contains("SCREENING_SOURCE_UNAVAILABLE");
        assertThat((String) ((Map<String, Object>) ((Map<String, Object>) v2.get("responses")).get("422")).get("description"))
                .contains("CUSTOMER_NOT_FOUND");

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        Map<String, Object> request = (Map<String, Object>) schemas.get("ComputedScreeningRequest");
        assertThat((List<String>) request.get("required")).containsExactlyInAnyOrder("transactionId", "customerId", "amount", "currency");
        assertThat(((Map<String, Object>) request.get("properties")).keySet())
                .as("the caller states no screening fact on v2")
                .containsExactlyInAnyOrder("transactionId", "customerId", "amount", "currency");

        Map<String, Object> response = (Map<String, Object>) schemas.get("ComplianceScreeningResponse");
        Map<String, Object> properties = (Map<String, Object>) response.get("properties");
        assertThat((String) ((Map<String, Object>) properties.get("attestation")).get("description")).contains("COMPUTED");
        assertThat((Map<String, Object>) properties.get("listVersions")).containsEntry("type", "array");
        Map<String, Object> listVersion = (Map<String, Object>) schemas.get("ListVersion");
        assertThat((List<String>) listVersion.get("required")).containsExactlyInAnyOrder("list", "source", "version", "loadedAt");
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

    @Test
    @SuppressWarnings("unchecked")
    void sanctionsAndPepFlagsAreRequiredWithoutADefault() throws IOException {
        Map<String, Object> spec = new Yaml().load(loadSpec());
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        Map<String, Object> request = (Map<String, Object>) schemas.get("ComplianceScreeningRequest");
        Map<String, Object> properties = (Map<String, Object>) request.get("properties");

        assertThat((List<String>) request.get("required")).contains("currency", "sanctionsHit", "kycVerified", "pep");
        assertThat((Map<String, Object>) properties.get("currency")).doesNotContainKey("default");
        assertThat((Map<String, Object>) properties.get("sanctionsHit")).doesNotContainKey("default");
        assertThat((Map<String, Object>) properties.get("pep")).doesNotContainKey("default");
        assertThat((Map<String, Object>) properties.get("kycVerified")).doesNotContainKey("default");
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
