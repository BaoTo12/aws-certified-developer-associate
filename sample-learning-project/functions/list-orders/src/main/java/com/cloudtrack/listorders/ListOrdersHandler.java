package com.cloudtrack.listorders;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.cloudtrack.common.dto.ListOrdersResponse;
import com.cloudtrack.common.model.Order;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class ListOrdersHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public ListOrdersHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public ListOrdersHandler(DynamoDbClient dynamoDbClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        try {
            Map<String, String> pathParams = input.getPathParameters();
            String customerId = (pathParams != null) ? pathParams.get("customerId") : null;

            if (customerId == null || customerId.isBlank()) {
                return buildResponse(400, "{\"error\": \"customerId path parameter is required\"}");
            }

            Map<String, String> queryParams = (input.getQueryStringParameters() != null) ? input.getQueryStringParameters() : Collections.emptyMap();
            int limit = 10;
            if (queryParams.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(queryParams.get("limit"));
                } catch (NumberFormatException ignored) {}
            }

            String nextToken = queryParams.get("nextToken");

            String tableName = parameterStoreUtil.getParameter("/cloudtrack/orders-table-name", "Orders");

            QueryRequest.Builder queryBuilder = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName("CustomerIndex")
                    .keyConditionExpression("customerId = :c")
                    .expressionAttributeValues(Map.of(":c", AttributeValue.builder().s(customerId).build()))
                    .limit(limit);

            // Handle pagination ExclusiveStartKey from base64 nextToken
            if (nextToken != null && !nextToken.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(nextToken);
                Map<String, String> rawKey = objectMapper.readValue(decoded, new TypeReference<Map<String, String>>() {});
                Map<String, AttributeValue> startKey = new HashMap<>();
                for (Map.Entry<String, String> entry : rawKey.entrySet()) {
                    startKey.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
                }
                queryBuilder.exclusiveStartKey(startKey);
            }

            QueryResponse queryResponse = dynamoDbClient.query(queryBuilder.build());

            List<Order> orders = new ArrayList<>();
            for (Map<String, AttributeValue> item : queryResponse.items()) {
                Order order = new Order();
                if (item.containsKey("orderId")) order.setOrderId(item.get("orderId").s());
                if (item.containsKey("customerId")) order.setCustomerId(item.get("customerId").s());
                if (item.containsKey("status")) order.setStatus(item.get("status").s());
                if (item.containsKey("createdAt")) order.setCreatedAt(item.get("createdAt").s());
                if (item.containsKey("executionArn")) order.setExecutionArn(item.get("executionArn").s());
                orders.add(order);
            }

            String newNextToken = null;
            if (queryResponse.hasLastEvaluatedKey() && !queryResponse.lastEvaluatedKey().isEmpty()) {
                Map<String, String> keyToSerialize = new HashMap<>();
                for (Map.Entry<String, AttributeValue> entry : queryResponse.lastEvaluatedKey().entrySet()) {
                    if (entry.getValue().s() != null) {
                        keyToSerialize.put(entry.getKey(), entry.getValue().s());
                    }
                }
                byte[] jsonBytes = objectMapper.writeValueAsBytes(keyToSerialize);
                newNextToken = Base64.getEncoder().encodeToString(jsonBytes);
            }

            ListOrdersResponse response = new ListOrdersResponse(orders, newNextToken);
            return buildResponse(200, objectMapper.writeValueAsString(response));

        } catch (Exception e) {
            context.getLogger().log("Error in ListOrdersHandler: " + e.getMessage());
            return buildResponse(500, "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body)
                .build();
    }
}
