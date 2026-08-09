package com.cloudtrack.processorder;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.cloudtrack.common.model.WorkflowStatus;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProcessOrderHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final SfnClient sfnClient;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public ProcessOrderHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.sfnClient = SfnClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public ProcessOrderHandler(DynamoDbClient dynamoDbClient, SfnClient sfnClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.sfnClient = sfnClient;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> batchItemFailures = new ArrayList<>();
        String tableName = parameterStoreUtil.getParameter("/cloudtrack/orders-table-name", "Orders");
        String stateMachineArn = parameterStoreUtil.getParameter("/cloudtrack/state-machine-arn", System.getenv("STATE_MACHINE_ARN"));

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                JsonNode messageNode = objectMapper.readTree(msg.getBody());
                String orderId = messageNode.has("orderId") ? messageNode.get("orderId").asText() : null;

                if (orderId == null || orderId.isBlank()) {
                    context.getLogger().log("Skipping message without orderId: " + msg.getMessageId());
                    continue;
                }

                // Start Step Functions state machine execution
                StartExecutionResponse startResp = sfnClient.startExecution(StartExecutionRequest.builder()
                        .stateMachineArn(stateMachineArn)
                        .name("OrderExec-" + orderId + "-" + System.currentTimeMillis())
                        .input(msg.getBody())
                        .build());

                String executionArn = startResp.executionArn();

                // Update Order in DynamoDB with executionArn and status=IN_PROGRESS
                dynamoDbClient.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("orderId", AttributeValue.builder().s(orderId).build()))
                        .updateExpression("SET executionArn = :arn, #s = :status")
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(Map.of(
                                ":arn", AttributeValue.builder().s(executionArn).build(),
                                ":status", AttributeValue.builder().s(WorkflowStatus.IN_PROGRESS.name()).build()
                        ))
                        .build());

            } catch (Exception e) {
                context.getLogger().log("Error processing message " + msg.getMessageId() + ": " + e.getMessage());
                // Add to batch item failures so SQS only retries this specific message
                batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(msg.getMessageId()));
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(batchItemFailures).build();
    }
}
