package com.cloudtrack.createorder;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.cloudtrack.common.dto.CreateOrderRequest;
import com.cloudtrack.common.dto.OrderResponse;
import com.cloudtrack.common.model.OrderItem;
import com.cloudtrack.common.model.WorkflowStatus;
import com.cloudtrack.common.util.KmsUtil;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.util.*;

public class CreateOrderHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;
    private final KmsUtil kmsUtil;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public CreateOrderHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.sqsClient = SqsClient.create();
        KmsClient kmsClient = KmsClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.kmsUtil = new KmsUtil(kmsClient, System.getenv("KMS_KEY_ID"));
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    // For testing/injection
    public CreateOrderHandler(DynamoDbClient dynamoDbClient, SqsClient sqsClient, KmsUtil kmsUtil, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.sqsClient = sqsClient;
        this.kmsUtil = kmsUtil;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        try {
            if (input.getBody() == null || input.getBody().isBlank()) {
                return buildResponse(400, "{\"error\": \"Missing request body\"}");
            }

            CreateOrderRequest request = objectMapper.readValue(input.getBody(), CreateOrderRequest.class);
            if (request.getCustomerId() == null || request.getItems() == null || request.getItems().isEmpty()) {
                return buildResponse(400, "{\"error\": \"customerId and items are required\"}");
            }

            String orderId = "ord-" + UUID.randomUUID().toString().substring(0, 8);
            String encryptedEmail = kmsUtil.encrypt(request.getCustomerEmail());
            String createdAt = Instant.now().toString();
            String tableName = parameterStoreUtil.getParameter("/cloudtrack/orders-table-name", "Orders");
            String orderQueueUrl = parameterStoreUtil.getParameter("/cloudtrack/order-queue-url", System.getenv("ORDER_QUEUE_URL"));

            // Build DynamoDB PutItem request with attribute_not_exists(orderId) condition for idempotency
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.builder().s(orderId).build());
            item.put("customerId", AttributeValue.builder().s(request.getCustomerId()).build());
            if (encryptedEmail != null) {
                item.put("customerEmail", AttributeValue.builder().s(encryptedEmail).build());
            }
            item.put("status", AttributeValue.builder().s(WorkflowStatus.PENDING.name()).build());
            item.put("createdAt", AttributeValue.builder().s(createdAt).build());

            // Serialize items
            List<AttributeValue> itemList = new ArrayList<>();
            for (OrderItem orderItem : request.getItems()) {
                Map<String, AttributeValue> itemMap = new HashMap<>();
                itemMap.put("sku", AttributeValue.builder().s(orderItem.getSku()).build());
                itemMap.put("qty", AttributeValue.builder().n(String.valueOf(orderItem.getQty())).build());
                itemList.add(AttributeValue.builder().m(itemMap).build());
            }
            item.put("items", AttributeValue.builder().l(itemList).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(orderId)")
                    .build();

            dynamoDbClient.putItem(putItemRequest);

            // Send orderId to SQS OrderQueue
            if (orderQueueUrl != null && !orderQueueUrl.isBlank()) {
                Map<String, Object> queuePayload = new HashMap<>();
                queuePayload.put("orderId", orderId);
                queuePayload.put("customerId", request.getCustomerId());
                queuePayload.put("items", request.getItems());
                
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(orderQueueUrl)
                        .messageBody(objectMapper.writeValueAsString(queuePayload))
                        .build());
            }

            OrderResponse responseDto = new OrderResponse(orderId, WorkflowStatus.PENDING.name());
            responseDto.setCustomerId(request.getCustomerId());
            responseDto.setCreatedAt(createdAt);

            return buildResponse(201, objectMapper.writeValueAsString(responseDto));

        } catch (ConditionalCheckFailedException e) {
            return buildResponse(409, "{\"error\": \"Order ID already exists (idempotency rejection)\"}");
        } catch (Exception e) {
            context.getLogger().log("Error in CreateOrderHandler: " + e.getMessage());
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
