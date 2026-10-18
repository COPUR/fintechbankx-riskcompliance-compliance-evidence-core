package com.bank.compliance.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The switch of the startup assertions. They are on by default, wherever the
 * service or its migrate-only Job runs: a runtime outside the chart gets the
 * same refusal as a pod. Only two things turn the TLS assertions
 * ({@link DatabaseTlsGuard}, {@link KafkaTlsGuard}) off, and neither can come
 * from the chart (the vendored guard refuses every profile and
 * fintechbankx.tls.* config key):
 * <ul>
 * <li>the Spring profile {@value #LOCAL_PROFILE}, by its exact name (a
 * developer machine or an ephemeral environment without the RDS CA bundle);</li>
 * <li>the property {@value #ENFORCE_PROPERTY} with the exact value
 * {@code false}, set only in test resources or test annotations, never in a
 * shipped application*.yml.</li>
 * </ul>
 * When they are off, {@link OffWarning} logs it at WARN before any connection
 * exists. The service's runtime role assertion is off with the local profile
 * only (a single-user local run is its own schema owner): tests connect as a
 * least-privilege runtime role and need no switch for it, so the test
 * property leaves it on ({@link NotLocal}).
 */
public final class StartupAssertions {

    public static final String ENFORCE_PROPERTY = "fintechbankx.tls.enforce";
    public static final String LOCAL_PROFILE = "local";
    static final String OFF_MESSAGE = "TLS startup assertions are off (profile " + LOCAL_PROFILE + " or "
            + ENFORCE_PROPERTY + "=false): the database URL, the RDS CA bundle and the Kafka producer protocol are"
            + " not checked. Only a local run or a test may start this way.";

    private StartupAssertions() {
    }

    /** The TLS assertions: on unless the local profile or the test switch turns them off. */
    public static boolean enabled(Environment environment) {
        boolean switchedOff = "false".equals(environment.getProperty(ENFORCE_PROPERTY, "").trim());
        return !local(environment) && !switchedOff;
    }

    public static boolean local(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE));
    }

    /** {@code @Conditional}: the assertions are on (the default). */
    public static final class On implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return enabled(context.getEnvironment());
        }
    }

    /** {@code @Conditional}: the local profile or the test switch turned them off. */
    public static final class Off implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !enabled(context.getEnvironment());
        }
    }

    /** {@code @Conditional}: not the local profile (the runtime role assertion; the test switch leaves it on). */
    public static final class NotLocal implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !local(context.getEnvironment());
        }
    }

    /** Registered in place of the guards when they are off: says so once, at the stage the guards would run. */
    public static final class OffWarning implements BeanFactoryPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(StartupAssertions.class);

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            log.warn(OFF_MESSAGE);
        }
    }
}
