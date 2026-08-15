package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

public class IamSetup {

    private static final Logger logger = LoggerFactory.getLogger(IamSetup.class);
    private static final String IAM_ROLE_PREFIX = "[IAM] Role ";

    private IamSetup() {
        // Utility class private constructor
    }

    public static String setupLambdaRole(IamClient iamClient, String roleName) {
        try {
            GetRoleResponse existingRole = iamClient.getRole(r -> r.roleName(roleName));
            logger.info("{}{} already exists: {}", IAM_ROLE_PREFIX, roleName, existingRole.role().arn());
            return existingRole.role().arn();
        } catch (NoSuchEntityException e) {
            logger.info("[IAM] Creating role {}...", roleName);
        }

        String trustPolicy = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": { "Service": "lambda.amazonaws.com" },
                  "Action": "sts:AssumeRole"
                }
              ]
            }""";

        CreateRoleResponse createRoleResponse = iamClient.createRole(r -> r
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("Execution role for CloudTrack Lambda function " + roleName));

        // Attach basic Lambda execution policy
        iamClient.attachRolePolicy(r -> r
                .roleName(roleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"));

        logger.info("[IAM] Created role {}: {}", roleName, createRoleResponse.role().arn());

        // Short pause to allow IAM role propagation across AWS regions
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("IAM role propagation sleep interrupted", e);
        }

        return createRoleResponse.role().arn();
    }

    public static String setupStepFunctionsRole(IamClient iamClient, String roleName) {
        try {
            GetRoleResponse existingRole = iamClient.getRole(r -> r.roleName(roleName));
            logger.info("{}{} already exists: {}", IAM_ROLE_PREFIX, roleName, existingRole.role().arn());
            return existingRole.role().arn();
        } catch (NoSuchEntityException e) {
            logger.info("[IAM] Creating Step Functions role {}...", roleName);
        }

        String trustPolicy = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": { "Service": "states.amazonaws.com" },
                  "Action": "sts:AssumeRole"
                }
              ]
            }""";

        CreateRoleResponse createRoleResponse = iamClient.createRole(r -> r
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy));

        String inlinePolicy = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Action": "lambda:InvokeFunction",
                  "Resource": "arn:aws:lambda:*:*:function:*"
                }
              ]
            }""";

        iamClient.putRolePolicy(r -> r
                .roleName(roleName)
                .policyName("StepFunctionsLambdaInvokePolicy")
                .policyDocument(inlinePolicy));

        logger.info("[IAM] Created Step Functions role {}: {}", roleName, createRoleResponse.role().arn());
        return createRoleResponse.role().arn();
    }
}
