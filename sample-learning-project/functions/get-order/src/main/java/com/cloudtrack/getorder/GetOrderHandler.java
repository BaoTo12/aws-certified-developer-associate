package com.cloudtrack.getorder;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.cloudtrack.common.dto.OrderResponse;
import com.cloudtrack.common.model.OrderItem;
import com.cloudtrack.common.util.KmsUtil;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.*;

public class GetOrderHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final KmsUtil kmsUtil;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public GetOrderHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        KmsClient kmsClient = KmsClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.kmsUtil = new KmsUtil(kmsClient, System.getenv("KMS_KEY_ID"));
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public GetOrderHandler(DynamoDbClient dynamoDbClient, KmsUtil kmsUtil, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.kmsUtil = kmsUtil;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        try {
            Map<String, String> pathParams = input.getPathParameters();
            String orderId = (pathParams != null) ? pathParams.get("orderId") : null;

            if (orderId == null || orderId.isBlank()) {
                return buildResponse(400, "{\"error\": \"orderId path parameter is required\"}");
            }

            String tableName = parameterStoreUtil.getParameter("/cloudtrack/orders-table-name", "Orders");

            GetItemResponse getItemResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("orderId", AttributeValue.builder().s(orderId).build()))
                    .build());

            if (!getItemResponse.hasItem() || getItemResponse.item().isEmpty()) {
                return buildResponse(404, "{\"error\": \"Order not found\"}");
            }

            Map<String, AttributeValue> item = getItemResponse.item();
            OrderResponse response = new OrderResponse();
            response.setOrderId(orderId);
            if (item.containsKey("customerId")) response.setCustomerId(item.get("customerId").s());
            if (item.containsKey("status")) response.setStatus(item.get("status").s());
            if (item.containsKey("createdAt")) response.setCreatedAt(item.get("createdAt").s());
            if (item.containsKey("executionArn")) response.setExecutionArn(item.get("executionArn").s());

            if (item.containsKey("customerEmail")) {
                String encryptedEmail = item.get("customerEmail").s();
                response.setCustomerEmail(kmsUtil.decrypt(encryptedEmail));
            }

            if (item.containsKey("items") && item.get("items").hasL()) {
                List<OrderItem> orderItems = new ArrayList<>();
                for (AttributeValue av : item.get("items").l()) {
                    if (av.hasM()) {
                        Map<String, AttributeValue> m = av.m();
                        String sku = m.containsKey("sku") ? m.get("sku").s() : "";
                        int qty = m.containsKey("qty") ? Integer.parseInt(m.get("qty").n()) : 0;
                        orderItems.add(new OrderItem(sku, qty));
                    }
                }
                response.setItems(orderItems);
            }

            return buildResponse(200, objectMapper.writeValueAsString(response));

        } catch (Exception e) {
            context.getLogger().log("Error in GetOrderHandler: " + e.getMessage());
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
