package com.bank.compliance.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TLS startup assertions (DatabaseTlsGuard, KafkaTlsGuard) are on by
 * default: a runtime outside the chart that starts the service with the
 * application.yml defaults (no profile, no DB_SSL_ROOT_CERT, a plain
 * localhost datasource URL, a PLAINTEXT producer) does not start. Only the
 * local profile, or the test switch fintechbankx.tls.enforce=false set in test
 * resources or test annotations, turns them off, and the log then says so.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupTlsAssertionsTest {

    private static final String OFF = "TLS startup assertions are off";

    /** What application.yml resolves to without any environment: the local single-user shape. */
    private final ApplicationContextRunner localShape = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(DatabaseTlsConfiguration.class, KafkaTlsConfiguration.class)
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_cmp_evidence_local",
                    "spring.kafka.security.protocol=PLAINTEXT");

    @Test
    void refusesToStartWithNoProfileAndNoBundle(CapturedOutput output) {
        localShape.run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle"));
        assertThat(output.getOut()).doesNotContain(OFF);
    }

    @Test
    void startsWithTheLocalProfileAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        localShape.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DatabaseTlsGuard.class).doesNotHaveBean(KafkaTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    @Test
    void startsWithTheTestSwitchAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        localShape.withPropertyValues("fintechbankx.tls.enforce=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DatabaseTlsGuard.class).doesNotHaveBean(KafkaTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    /** The local profile counts among others too, but another profile alone does not. */
    @Test
    void theLocalProfileCountsOnlyByItsExactName() {
        localShape.withPropertyValues("spring.profiles.active=kafka-msk,local")
                .run(context -> assertThat(context).hasNotFailed());
        localShape.withPropertyValues("spring.profiles.active=kafka-msk")
                .run(context -> assertThat(context).hasFailed());
    }
}
