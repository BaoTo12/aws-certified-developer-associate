package com.cloudtrack.reservestock;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.util.ParameterStoreUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.*;

public class ReserveStockHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient;
    private final ParameterStoreUtil parameterStoreUtil;

    public ReserveStockHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
    }

    public ReserveStockHandler(DynamoDbClient dynamoDbClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.parameterStoreUtil = parameterStoreUtil;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> result = new HashMap<>(input);
        List<Map<String, Object>> items = (List<Map<String, Object>>) input.get("items");

        String inventoryTableName = parameterStoreUtil.getParameter("/cloudtrack/inventory-table-name", "Inventory");

        List<Map<String, Object>> reservedItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        boolean allReserved = true;

        if (items != null) {
            for (Map<String, Object> item : items) {
                String sku = (String) item.get("sku");
                int qty = ((Number) item.get("qty")).intValue();

                try {
                    // UpdateItem with conditional check: stock >= :qty
                    dynamoDbClient.updateItem(UpdateItemRequest.builder()
                            .tableName(inventoryTableName)
                            .key(Map.of("sku", AttributeValue.builder().s(sku).build()))
                            .updateExpression("SET stock = stock - :qty")
                            .conditionExpression("stock >= :qty")
                            .expressionAttributeValues(Map.of(":qty", AttributeValue.builder().n(String.valueOf(qty)).build()))
                            .build());

                    reservedItems.add(Map.of("sku", sku, "qty", qty));

                } catch (ConditionalCheckFailedException e) {
                    allReserved = false;
                    failedItems.add(Map.of("sku", sku, "qty", qty, "reason", "Insufficient stock at reservation time"));
                    context.getLogger().log("Reservation failed for SKU: " + sku);
                } catch (Exception e) {
                    allReserved = false;
                    failedItems.add(Map.of("sku", sku, "qty", qty, "reason", e.getMessage()));
                }
            }
        }

        result.put("allReserved", allReserved);
        result.put("reservedItems", reservedItems);
        result.put("failedItems", failedItems);
        return result;
    }
}
