package com.cloudtrack.getorderstatus;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.cloudtrack.common.dto.OrderStatusResponse;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.HashMap;
import java.util.Map;

public class GetOrderStatusHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final SfnClient sfnClient;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public GetOrderStatusHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.sfnClient = SfnClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public GetOrderStatusHandler(DynamoDbClient dynamoDbClient, SfnClient sfnClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.sfnClient = sfnClient;
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
            if (!item.containsKey("executionArn") || item.get("executionArn").s() == null || item.get("executionArn").s().isBlank()) {
                return buildResponse(404, "{\"error\": \"No state machine execution started for this order yet\"}");
            }

            String executionArn = item.get("executionArn").s();

            DescribeExecutionResponse desc = sfnClient.describeExecution(DescribeExecutionRequest.builder()
                    .executionArn(executionArn)
                    .build());

            OrderStatusResponse statusResponse = new OrderStatusResponse();
            statusResponse.setOrderId(orderId);
            statusResponse.setWorkflowStatus(desc.statusAsString());
            statusResponse.setStartDate(desc.startDate() != null ? desc.startDate().toString() : null);
            statusResponse.setStopDate(desc.stopDate() != null ? desc.stopDate().toString() : null);

            return buildResponse(200, objectMapper.writeValueAsString(statusResponse));

        } catch (Exception e) {
            context.getLogger().log("Error in GetOrderStatusHandler: " + e.getMessage());
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
