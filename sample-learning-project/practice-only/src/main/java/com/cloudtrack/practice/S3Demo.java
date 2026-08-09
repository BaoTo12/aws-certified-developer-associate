package com.cloudtrack.practice;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class S3Demo {

    public static void main(String[] args) {
        String bucketName = "cloudtrack-practice-bucket-" + System.currentTimeMillis();
        String objectKey = "reports/sample-report.txt";
        System.out.println("=== Amazon S3 Practice Demo ===");

        try (S3Client s3Client = S3Client.create();
             S3Presigner presigner = S3Presigner.create()) {

            // 1. CreateBucket
            System.out.println("Creating bucket: " + bucketName);
            s3Client.createBucket(r -> r.bucket(bucketName));
            System.out.println("Bucket created.");

            // 2. PutObject
            System.out.println("Uploading object: " + objectKey);
            String content = "CloudTrack sample report content generated at " + System.currentTimeMillis();
            s3Client.putObject(r -> r
                            .bucket(bucketName)
                            .key(objectKey)
                            .contentType("text/plain"),
                    RequestBody.fromString(content, StandardCharsets.UTF_8));
            System.out.println("Object uploaded.");

            // 3. GetObject
            System.out.println("Downloading object...");
            String downloadedContent = new String(s3Client.getObjectAsBytes(r -> r
                    .bucket(bucketName)
                    .key(objectKey)).asByteArray(), StandardCharsets.UTF_8);
            System.out.println("Downloaded content: " + downloadedContent);

            // 4. GeneratePresignedUrl (Time-limited download URL)
            System.out.println("Generating presigned GET URL (valid for 10 minutes)...");
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            System.out.println("Presigned URL: " + presignedRequest.url());

            // 5. ListObjectsV2
            System.out.println("Listing objects in bucket...");
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(r -> r.bucket(bucketName));
            for (S3Object obj : listResponse.contents()) {
                System.out.println(" - " + obj.key() + " (" + obj.size() + " bytes)");
            }

            // 6. DeleteObjects (Batch delete)
            System.out.println("Deleting object(s)...");
            s3Client.deleteObjects(r -> r
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(List.of(ObjectIdentifier.builder().key(objectKey).build())).build()));

            // 7. DeleteBucket
            System.out.println("Deleting bucket...");
            s3Client.deleteBucket(r -> r.bucket(bucketName));
            System.out.println("S3 Bucket deleted cleanly.");

        } catch (Exception e) {
            System.err.println("S3 demo error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
