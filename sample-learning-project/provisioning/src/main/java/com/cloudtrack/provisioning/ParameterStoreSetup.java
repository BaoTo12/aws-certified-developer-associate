package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

public class ParameterStoreSetup {

    private static final Logger logger = LoggerFactory.getLogger(ParameterStoreSetup.class);

    private ParameterStoreSetup() {
        // Utility class private constructor
    }

    public static void putParameter(SsmClient ssm, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        logger.info("[SSM Parameter Store] Setting {} = {}", name, value);
        ssm.putParameter(r -> r
                .name(name)
                .type(ParameterType.STRING)
                .value(value)
                .overwrite(true));
    }
}
