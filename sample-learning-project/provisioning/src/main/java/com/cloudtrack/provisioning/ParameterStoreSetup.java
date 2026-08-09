package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

public class ParameterStoreSetup {

    public static void putParameter(SsmClient ssm, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        System.out.println("[SSM Parameter Store] Setting " + name + " = " + value);
        ssm.putParameter(r -> r
                .name(name)
                .type(ParameterType.STRING)
                .value(value)
                .overwrite(true));
    }
}
