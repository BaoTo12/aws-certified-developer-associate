package com.cloudtrack.checkinventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.util.ParameterStoreUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.*;

public class CheckInventoryHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final DynamoDbClient dynamoDbClient;
    private final ParameterStoreUtil parameterStoreUtil;

    public CheckInventoryHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
    }

    public CheckInventoryHandler(DynamoDbClient dynamoDbClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.parameterStoreUtil = parameterStoreUtil;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> result = new HashMap<>(input);
        List<Map<String, Object>> items = (List<Map<String, Object>>) input.get("items");

        if (items == null || items.isEmpty()) {
            result.put("inventoryCheckPassed", true);
            result.put("itemsAvailability", Collections.emptyList());
            return result;
        }

        String inventoryTableName = parameterStoreUtil.getParameter("/cloudtrack/inventory-table-name", "Inventory");

        // Build keys for BatchGetItem
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        Map<String, Integer> requestedQtyMap = new HashMap<>();

        for (Map<String, Object> item : items) {
            String sku = (String) item.get("sku");
            int qty = ((Number) item.get("qty")).intValue();
            keys.add(Map.of("sku", AttributeValue.builder().s(sku).build()));
            requestedQtyMap.put(sku, qty);
        }

        BatchGetItemRequest batchGetItemRequest = BatchGetItemRequest.builder()
                .requestItems(Map.of(inventoryTableName, KeysAndAttributes.builder().keys(keys).build()))
                .build();

        BatchGetItemResponse batchGetItemResponse = dynamoDbClient.batchGetItem(batchGetItemRequest);
        List<Map<String, AttributeValue>> returnedItems = batchGetItemResponse.responses().getOrDefault(inventoryTableName, Collections.emptyList());

        Map<String, Integer> availableStockMap = new HashMap<>();
        for (Map<String, AttributeValue> returnedItem : returnedItems) {
            String sku = returnedItem.get("sku").s();
            int stock = Integer.parseInt(returnedItem.get("stock").n());
            availableStockMap.put(sku, stock);
        }

        boolean allAvailable = true;
        List<Map<String, Object>> availabilityList = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : requestedQtyMap.entrySet()) {
            String sku = entry.getKey();
            int requestedQty = entry.getValue();
            int availableStock = availableStockMap.getOrDefault(sku, 0);
            boolean isSufficient = availableStock >= requestedQty;

            if (!isSufficient) {
                allAvailable = false;
            }

            Map<String, Object> check = new HashMap<>();
            check.put("sku", sku);
            check.put("requestedQty", requestedQty);
            check.put("availableStock", availableStock);
            check.put("sufficient", isSufficient);
            availabilityList.add(check);
        }

        result.put("inventoryCheckPassed", allAvailable);
        result.put("itemsAvailability", availabilityList);
        return result;
    }
}
