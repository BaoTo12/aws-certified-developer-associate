package com.cloudtrack.rollbackreservations;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.util.ParameterStoreUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.*;

public class RollbackReservationsHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient;
    private final ParameterStoreUtil parameterStoreUtil;

    public RollbackReservationsHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
    }

    public RollbackReservationsHandler(DynamoDbClient dynamoDbClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.parameterStoreUtil = parameterStoreUtil;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> result = new HashMap<>(input);
        List<Map<String, Object>> reservedItems = (List<Map<String, Object>>) input.get("reservedItems");

        String inventoryTableName = parameterStoreUtil.getParameter("/cloudtrack/inventory-table-name", "Inventory");
        List<String> rolledBackSkus = new ArrayList<>();

        if (reservedItems != null) {
            for (Map<String, Object> item : reservedItems) {
                String sku = (String) item.get("sku");
                int qty = ((Number) item.get("qty")).intValue();

                try {
                    // Compensating transaction: add back the quantity to stock
                    dynamoDbClient.updateItem(UpdateItemRequest.builder()
                            .tableName(inventoryTableName)
                            .key(Map.of("sku", AttributeValue.builder().s(sku).build()))
                            .updateExpression("SET stock = stock + :qty")
                            .expressionAttributeValues(Map.of(":qty", AttributeValue.builder().n(String.valueOf(qty)).build()))
                            .build());

                    rolledBackSkus.add(sku);
                    context.getLogger().log("Successfully rolled back stock for SKU: " + sku + ", qty: " + qty);

                } catch (Exception e) {
                    context.getLogger().log("Error rolling back stock for SKU " + sku + ": " + e.getMessage());
                }
            }
        }

        result.put("rolledBackSkus", rolledBackSkus);
        result.put("rollbackCompleted", true);
        return result;
    }
}
