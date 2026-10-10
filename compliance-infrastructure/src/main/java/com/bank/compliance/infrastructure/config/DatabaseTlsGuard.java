package com.bank.compliance.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/** Skeleton: accepts every URL until the checks are implemented. */
public final class DatabaseTlsGuard implements BeanFactoryPostProcessor {

    static final String ROOT_CERT_PROPERTY = "DB_SSL_ROOT_CERT";

    private final Environment environment;

    DatabaseTlsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify();
    }

    void verify() {
    }
}
