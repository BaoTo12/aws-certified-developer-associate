package com.cloudtrack.common.util;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

public class ParameterStoreUtil {

    private final SsmClient ssmClient;

    public ParameterStoreUtil(SsmClient ssmClient) {
        this.ssmClient = ssmClient;
    }

    public String getParameter(String paramName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            String envValue = System.getenv(paramName.replace("/", "_").replace("-", "_").toUpperCase());
            return (envValue != null && !envValue.isBlank()) ? envValue : defaultValue;
        }
    }
}
