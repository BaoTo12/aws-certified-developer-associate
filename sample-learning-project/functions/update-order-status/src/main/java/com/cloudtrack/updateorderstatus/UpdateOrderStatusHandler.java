package com.cloudtrack.updateorderstatus;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.util.ParameterStoreUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class UpdateOrderStatusHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient;
    private final ParameterStoreUtil parameterStoreUtil;

    public UpdateOrderStatusHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
    }

    public UpdateOrderStatusHandler(DynamoDbClient dynamoDbClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.parameterStoreUtil = parameterStoreUtil;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> result = new HashMap<>(input);
        String orderId = (String) input.get("orderId");
        String calculatedStatus = (String) input.getOrDefault("calculatedStatus", "CONFIRMED");

        if (orderId == null || orderId.isBlank()) {
            context.getLogger().log("Missing orderId in UpdateOrderStatusHandler");
            result.put("statusUpdated", false);
            return result;
        }

        String tableName = parameterStoreUtil.getParameter("/cloudtrack/orders-table-name", "Orders");

        // TTL calculation: 90 days from now
        long expiresAtEpochSeconds = Instant.now().getEpochSecond() + (90L * 24 * 3600);

        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("orderId", AttributeValue.builder().s(orderId).build()))
                    .updateExpression("SET #s = :status, expiresAt = :exp")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":status", AttributeValue.builder().s(calculatedStatus).build(),
                            ":exp", AttributeValue.builder().n(String.valueOf(expiresAtEpochSeconds)).build()
                    ))
                    .build());

            result.put("statusUpdated", true);
            result.put("finalOrderStatus", calculatedStatus);
            context.getLogger().log("Updated order " + orderId + " status to " + calculatedStatus);

        } catch (Exception e) {
            context.getLogger().log("Error updating order status for " + orderId + ": " + e.getMessage());
            result.put("statusUpdated", false);
            result.put("updateError", e.getMessage());
        }

        return result;
    }
}
