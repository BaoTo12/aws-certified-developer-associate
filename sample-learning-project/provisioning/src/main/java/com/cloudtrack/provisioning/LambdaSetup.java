package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateEventSourceMappingResponse;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.CreateFunctionUrlConfigResponse;
import software.amazon.awssdk.services.lambda.model.FunctionResponseType;
import software.amazon.awssdk.services.lambda.model.FunctionUrlAuthType;
import software.amazon.awssdk.services.lambda.model.GetFunctionResponse;
import software.amazon.awssdk.services.lambda.model.GetFunctionUrlConfigResponse;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.TracingMode;

import java.nio.file.Files;
import java.nio.file.Path;

public class LambdaSetup {

    private static final Logger logger = LoggerFactory.getLogger(LambdaSetup.class);
    private static final String LAMBDA_PREFIX = "[Lambda] ";

    private LambdaSetup() {
        // Utility class private constructor
    }

    public static String createOrUpdateFunction(LambdaClient lambda, String functionName, String handler, String roleArn, String jarPathStr) {
        logger.info("{}Setting up function {}...", LAMBDA_PREFIX, functionName);

        byte[] jarBytes = readJarBytes(jarPathStr);

        try {
            GetFunctionResponse getResp = lambda.getFunction(r -> r.functionName(functionName));
            logger.info("{}Function {} already exists: {}", LAMBDA_PREFIX, functionName, getResp.configuration().functionArn());
            return getResp.configuration().functionArn();
        } catch (ResourceNotFoundException e) {
            logger.info("{}Creating function {}...", LAMBDA_PREFIX, functionName);
        }

        CreateFunctionResponse createResp = lambda.createFunction(r -> r
                .functionName(functionName)
                .runtime(software.amazon.awssdk.services.lambda.model.Runtime.JAVA21)
                .role(roleArn)
                .handler(handler)
                .code(c -> c.zipFile(SdkBytes.fromByteArray(jarBytes)))
                .timeout(30)
                .memorySize(512)
                .tracingConfig(tc -> tc.mode(TracingMode.ACTIVE)));

        logger.info("{}Function {} created: {}", LAMBDA_PREFIX, functionName, createResp.functionArn());

        return createResp.functionArn();
    }

    private static byte[] readJarBytes(String jarPathStr) {
        try {
            Path jarPath = Path.of(jarPathStr);
            if (Files.exists(jarPath)) {
                return Files.readAllBytes(jarPath);
            } else {
                logger.warn("{}Warning: JAR not found at {}. Using dummy bytes for static verification structure.", LAMBDA_PREFIX, jarPathStr);
                return new byte[]{0};
            }
        } catch (Exception e) {
            logger.warn("{}Error reading JAR at {}: {}", LAMBDA_PREFIX, jarPathStr, e.getMessage());
            return new byte[]{0};
        }
    }

    public static String createFunctionUrl(LambdaClient lambda, String functionName) {
        try {
            CreateFunctionUrlConfigResponse resp = lambda.createFunctionUrlConfig(r -> r
                    .functionName(functionName)
                    .authType(FunctionUrlAuthType.AWS_IAM));
            logger.info("{}Function URL created for {}: {}", LAMBDA_PREFIX, functionName, resp.functionUrl());
            return resp.functionUrl();
        } catch (ResourceConflictException e) {
            GetFunctionUrlConfigResponse resp = lambda.getFunctionUrlConfig(r -> r.functionName(functionName));
            logger.info("{}Function URL exists for {}: {}", LAMBDA_PREFIX, functionName, resp.functionUrl());
            return resp.functionUrl();
        }
    }

    public static void createSqsEventSourceMapping(LambdaClient lambda, String functionName, String eventSourceArn) {
        try {
            CreateEventSourceMappingResponse resp = lambda.createEventSourceMapping(r -> r
                    .functionName(functionName)
                    .eventSourceArn(eventSourceArn)
                    .batchSize(10)
                    .functionResponseTypes(FunctionResponseType.REPORT_BATCH_ITEM_FAILURES));
            logger.info("{}Event source mapping created for {} -> {}", LAMBDA_PREFIX, functionName, resp.uuid());
        } catch (ResourceConflictException e) {
            logger.info("{}Event source mapping already exists for {}", LAMBDA_PREFIX, functionName);
        }
    }
}
