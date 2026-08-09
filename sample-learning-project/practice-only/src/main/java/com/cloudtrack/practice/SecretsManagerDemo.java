package com.cloudtrack.practice;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

public class SecretsManagerDemo {

    public static void main(String[] args) {
        String secretName = "cloudtrack/test-db-credentials-" + System.currentTimeMillis();
        System.out.println("=== Secrets Manager Practice Demo ===");

        try (SecretsManagerClient secretsClient = SecretsManagerClient.create()) {

            // 1. CreateSecret
            System.out.println("Creating secret: " + secretName);
            CreateSecretResponse createResponse = secretsClient.createSecret(r -> r
                    .name(secretName)
                    .description("Test database credentials for DVA-C02 practice")
                    .secretString("{\"username\":\"db_admin\",\"password\":\"initialPass123!\"}"));
            System.out.println("Secret created. ARN: " + createResponse.arn());

            // 2. GetSecretValue
            System.out.println("Retrieving secret value...");
            GetSecretValueResponse getResponse = secretsClient.getSecretValue(r -> r.secretId(secretName));
            System.out.println("Secret value retrieved: " + getResponse.secretString());

            // 3. PutSecretValue (Secret rotation simulation)
            System.out.println("Updating secret value (PutSecretValue)...");
            PutSecretValueResponse putResponse = secretsClient.putSecretValue(r -> r
                    .secretId(secretName)
                    .secretString("{\"username\":\"db_admin\",\"password\":\"rotatedPass456!\"}"));
            System.out.println("New secret version ID: " + putResponse.versionId());

            // 4. DeleteSecret (with ForceDeleteWithoutRecovery=true to tear down immediately and avoid monthly fees)
            System.out.println("Tearing down secret immediately (ForceDeleteWithoutRecovery=true)...");
            DeleteSecretResponse deleteResponse = secretsClient.deleteSecret(r -> r
                    .secretId(secretName)
                    .forceDeleteWithoutRecovery(true));
            System.out.println("Secret deleted cleanly at: " + deleteResponse.deletionDate());

        } catch (Exception e) {
            System.err.println("Secrets Manager demo error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
