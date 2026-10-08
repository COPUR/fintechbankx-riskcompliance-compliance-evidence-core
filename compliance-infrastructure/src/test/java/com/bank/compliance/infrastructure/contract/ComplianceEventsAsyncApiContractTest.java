package com.bank.compliance.infrastructure.contract;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.infrastructure.outbox.ComplianceEventEnvelopeFactory;
import com.bank.compliance.infrastructure.outbox.OutboxEventJpaEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The envelope the outbox writes matches the provider's AsyncAPI contract
 * (api/asyncapi/svc-cmp-evidence.yaml): channel, required envelope and data
 * fields, and the patterns and enums they declare.
 */
@SuppressWarnings("unchecked")
class ComplianceEventsAsyncApiContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void screenedEventMatchesTheContract() throws Exception {
        Map<String, Object> contract = load("svc-cmp-evidence.yaml");
        Map<String, Object> envelopeSchema = (Map<String, Object>) load("common/event-envelope.yaml").get("EventEnvelope");
        Map<String, Object> dataSchema = path(contract, "components", "schemas", "ComplianceScreenedData");

        ComplianceResult result = ComplianceResultFixtures.result("PAY-CT-1", "C-1", ComplianceDecision.REVIEW,
                List.of("PEP_HIGH_VALUE_REVIEW"));
        OutboxEventJpaEntity row = new ComplianceEventEnvelopeFactory(json).toOutboxRow(result.screenedEvent(), "corr-ct");
        JsonNode envelope = json.readTree(row.getPayload());
        JsonNode data = envelope.get("data");

        assertThat(row.getTopic()).isEqualTo(path(contract, "channels", "screened").get("address"));
        Map<String, Object> payload = path(contract, "components", "messages", "ComplianceScreened", "payload");
        Map<String, Object> constants = (Map<String, Object>) ((List<Object>) payload.get("allOf")).get(1);
        assertThat(envelope.get("eventType").asText())
                .isEqualTo(path(constants, "properties", "eventType").get("const"));
        assertThat(envelope.get("producer").asText())
                .isEqualTo(path(constants, "properties", "producer").get("const"));

        assertThat(fieldNames(envelope)).containsExactlyInAnyOrderElementsOf((List<String>) envelopeSchema.get("required"));
        assertThat(envelope.get("eventType").asText()).matches(pattern(envelopeSchema, "eventType"));
        assertThat(envelope.get("producer").asText()).matches(pattern(envelopeSchema, "producer"));
        assertThat(envelope.get("aggregateVersion").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(data.get("screeningId").asText());

        assertThat(fieldNames(data)).containsExactlyInAnyOrderElementsOf((List<String>) dataSchema.get("required"));
        assertThat(data.get("screeningId").asText()).matches(pattern(dataSchema, "screeningId"));
        assertThat((List<String>) path(dataSchema, "properties", "decision").get("enum"))
                .containsExactlyInAnyOrder(names(ComplianceDecision.values()));
        assertThat(((Map<String, Object>) dataSchema.get("properties")).keySet())
                .as("reason codes are restricted and stay out of the event contract")
                .doesNotContain("reasons", "reasonCodes");
    }

    /** The topic's .vN suffix is the contract's major version; a breaking change is a new topic. */
    @Test
    void infoVersionMajorMatchesTheTopicVersion() throws Exception {
        Map<String, Object> contract = load("svc-cmp-evidence.yaml");
        String version = (String) path(contract, "info").get("version");
        String topic = (String) path(contract, "channels", "screened").get("address");

        assertThat(topic).matches(".*\\.v[0-9]+$");
        assertThat(version.split("\\.")[0]).isEqualTo(topic.substring(topic.lastIndexOf(".v") + 2));
    }

    private static String pattern(Map<String, Object> schema, String property) {
        return (String) path(schema, "properties", property).get("pattern");
    }

    private static String[] names(ComplianceDecision[] values) {
        List<String> names = new ArrayList<>();
        for (ComplianceDecision value : values) {
            names.add(value.name());
        }
        return names.toArray(String[]::new);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Map<String, Object> path(Map<String, Object> root, String... keys) {
        Map<String, Object> current = root;
        for (String key : keys) {
            current = (Map<String, Object>) current.get(key);
            assertThat(current).as("contract path element %s", key).isNotNull();
        }
        return current;
    }

    private static Map<String, Object> load(String relative) throws IOException {
        for (String prefix : List.of("", "../", "../../", "../../../")) {
            Path candidate = Path.of(prefix + "api/asyncapi/" + relative);
            if (Files.exists(candidate)) {
                return new Yaml().load(Files.readString(candidate));
            }
        }
        throw new IOException("Unable to locate api/asyncapi/" + relative);
    }
}
