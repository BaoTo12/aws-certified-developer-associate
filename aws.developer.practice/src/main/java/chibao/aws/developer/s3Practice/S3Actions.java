package chibao.aws.developer.s3Practice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.waiters.S3AsyncWaiter;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;


public class S3Actions {
    private static Logger logger = LoggerFactory.getLogger(S3Actions.class);
    private static S3AsyncClient s3AsyncClient;


    public static S3AsyncClient getS3AsyncClient(){
        if (s3AsyncClient == null) {

            SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(50)
                    .readTimeout(Duration.ofSeconds(50))
                    .writeTimeout(Duration.ofSeconds(50))
                    .connectionTimeout(Duration.ofSeconds(50))
                    .build();

            // quản lý vòng đời của một API Call ở tầng SDK
            ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofMinutes(2))
                    .apiCallAttemptTimeout(Duration.ofSeconds(90))
                    .retryStrategy(RetryMode.STANDARD)
                    .build();

            s3AsyncClient = S3AsyncClient.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .httpClient(httpClient)
                    .overrideConfiguration(overrideConfiguration)
                    .build();
        }
        return s3AsyncClient;
    }

    public CompletableFuture<Void> createBucketAsync(String bucketName){
        CreateBucketRequest request = CreateBucketRequest.builder()
                .bucket(bucketName)
                .build();

        CompletableFuture<CreateBucketResponse> response = getS3AsyncClient().createBucket(request);
        return response.thenCompose(resp -> {
            S3AsyncWaiter s3Waiter = getS3AsyncClient().waiter();
            HeadBucketRequest bucketRequestWait = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            CompletableFuture<WaiterResponse<HeadBucketResponse>> waiterResponseFuture =
                    s3Waiter.waitUntilBucketExists(bucketRequestWait);
            return waiterResponseFuture.thenAccept(waiterResponse -> {
                waiterResponse.matched().response().ifPresent(headBucketResponse -> {
                    logger.info(bucketName + " is ready");
                });
            });
        }).whenComplete((resp, ex) -> {
            if (ex != null) {
                throw new RuntimeException("Failed to create bucket", ex);
            }
        });
    }


    public CompletableFuture<PutObjectResponse> uploadLocalFileAsync(String bucketName, String key, String objectPath){
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        CompletableFuture<PutObjectResponse> response = getS3AsyncClient().putObject(objectRequest, AsyncRequestBody.fromFile(Paths.get(objectPath)));

        return response.whenComplete((resp, ex) -> {
            if (ex != null) {
                throw new RuntimeException("Failed to upload file", ex);
            }
        });
    }

    public CompletableFuture<GetObjectResponse> getObjectToFileAsync(String bucketName, String key, String objectPath){
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return getS3AsyncClient().getObject(request, AsyncResponseTransformer.toFile(Paths.get(objectPath)));
    }

    public CompletableFuture<String> getPolicy(String bucket){
        GetBucketPolicyRequest request = GetBucketPolicyRequest.builder()
                .bucket(bucket)
                .build();

        CompletableFuture<GetBucketPolicyResponse> response = getS3AsyncClient().getBucketPolicy(request);
        return response.thenApply(GetBucketPolicyResponse::policy);
    }

    public CompletableFuture<Void> multipartUpload(String bucketName, String key){
        int mb = 1024 * 1024; // 1024MB
        CreateMultipartUploadRequest createMultipartUploadRequest = CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return getS3AsyncClient().createMultipartUpload(createMultipartUploadRequest)
                .thenCompose(createMultipartUploadResponse -> {
                    String uploadId = createMultipartUploadResponse.uploadId();
                    System.out.println("Upload ID: " + uploadId);

                    // upload part 1
                    UploadPartRequest uploadPartRequest1 = UploadPartRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .partNumber(1)
                            .contentLength((long) (5 * mb))
                            .build();
                    CompletableFuture<CompletedPart> part1Future = getS3AsyncClient()
                            .uploadPart(uploadPartRequest1, AsyncRequestBody.fromByteBuffer(getRandomByteBuffer(5 * mb)))
                            .thenApply(uploadPartResponse -> CompletedPart.builder()
                                    .partNumber(1)
                                    .eTag(uploadPartResponse.eTag())
                                    .build());
                    // upload part 2
                    UploadPartRequest uploadPartRequest2 = UploadPartRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .partNumber(2)
                            .contentLength((long) (3 * mb))
                            .build();

                    CompletableFuture<CompletedPart> part2Future = getS3AsyncClient()
                            .uploadPart(uploadPartRequest2, AsyncRequestBody.fromByteBuffer(getRandomByteBuffer(3 * mb)))
                            .thenApply(uploadPartResponse -> CompletedPart.builder()
                                    .partNumber(2)
                                    .eTag(uploadPartResponse.eTag())
                                    .build());

                    // Combine the results of both parts.
                    return CompletableFuture.allOf(part1Future, part2Future)
                            .thenCompose(v -> {
                                CompletedPart part1 = part1Future.join();
                                CompletedPart part2 = part2Future.join();

                                CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                                        .parts(part1, part2)
                                        .build();

                                CompleteMultipartUploadRequest completeMultipartUploadRequest = CompleteMultipartUploadRequest.builder()
                                        .bucket(bucketName)
                                        .key(key)
                                        .uploadId(uploadId)
                                        .multipartUpload(completedMultipartUpload)
                                        .build();

                                return getS3AsyncClient().completeMultipartUpload(completeMultipartUploadRequest);
                            })
                            .thenAccept(response -> System.out.println("Multipart upload completed successfully"))
                            .exceptionally(ex -> {
                                System.err.println("Failed to complete multipart upload: " + ex.getMessage());
                                throw new RuntimeException(ex);
                            });
                });
    }

    private static ByteBuffer getRandomByteBuffer(int size) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (int i = 0; i < size; i++) {
            buffer.put((byte) (Math.random() * 256));
        }
        buffer.flip();
        return buffer;
    }
}
