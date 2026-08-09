package com.cloudtrack.practice;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.nio.charset.StandardCharsets;

public class KmsCmkDemo {

    public static void main(String[] args) {
        System.out.println("=== AWS KMS Customer Managed Key (CMK) Practice Demo ===");

        try (KmsClient kmsClient = KmsClient.create()) {

            // 1. CreateKey (Customer Managed Key)
            System.out.println("Creating Customer Managed Key (CMK)...");
            CreateKeyResponse keyResponse = kmsClient.createKey(r -> r
                    .description("CloudTrack DVA-C02 practice CMK")
                    .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                    .origin(OriginType.AWS_KMS));

            String keyId = keyResponse.keyMetadata().keyId();
            String keyArn = keyResponse.keyMetadata().arn();
            System.out.println("CMK Created. Key ID: " + keyId + " | ARN: " + keyArn);

            // 2. Encrypt using CMK
            String plaintext = "Sensitive customer secret data";
            System.out.println("Encrypting data: '" + plaintext + "'");
            EncryptResponse encryptResponse = kmsClient.encrypt(r -> r
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromUtf8String(plaintext)));
            SdkBytes ciphertextBlob = encryptResponse.ciphertextBlob();
            System.out.println("Encrypted bytes length: " + ciphertextBlob.asByteArray().length);

            // 3. Decrypt using CMK
            System.out.println("Decrypting ciphertext...");
            DecryptResponse decryptResponse = kmsClient.decrypt(r -> r
                    .ciphertextBlob(ciphertextBlob)
                    .keyId(keyId));
            String decryptedText = decryptResponse.plaintext().asString(StandardCharsets.UTF_8);
            System.out.println("Decrypted text: " + decryptedText);

            // 4. ScheduleKeyDeletion (7-day minimum waiting period)
            System.out.println("Scheduling key deletion (7 days waiting period)...");
            ScheduleKeyDeletionResponse deletionResponse = kmsClient.scheduleKeyDeletion(r -> r
                    .keyId(keyId)
                    .pendingWindowInDays(7));
            System.out.println("Key deletion scheduled for: " + deletionResponse.deletionDate());

        } catch (Exception e) {
            System.err.println("KMS CMK demo error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
