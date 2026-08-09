package com.cloudtrack.common.util;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class KmsUtil {

    private final KmsClient kmsClient;
    private final String keyId;

    public KmsUtil(KmsClient kmsClient, String keyId) {
        this.kmsClient = kmsClient;
        this.keyId = (keyId != null && !keyId.isBlank()) ? keyId : "alias/aws/dynamodb";
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        EncryptRequest request = EncryptRequest.builder()
                .keyId(keyId)
                .plaintext(SdkBytes.fromUtf8String(plainText))
                .build();
        EncryptResponse response = kmsClient.encrypt(request);
        return Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray());
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isEmpty()) {
            return base64Ciphertext;
        }
        byte[] cipherBytes = Base64.getDecoder().decode(base64Ciphertext);
        DecryptRequest request = DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(cipherBytes))
                .build();
        DecryptResponse response = kmsClient.decrypt(request);
        return response.plaintext().asString(StandardCharsets.UTF_8);
    }
}
