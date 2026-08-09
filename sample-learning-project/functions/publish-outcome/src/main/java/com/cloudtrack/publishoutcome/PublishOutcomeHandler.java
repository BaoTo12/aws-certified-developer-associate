package com.cloudtrack.publishoutcome;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.model.WorkflowStatus;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.HashMap;
import java.util.Map;

public class PublishOutcomeHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final SnsClient snsClient;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public PublishOutcomeHandler() {
        this.snsClient = SnsClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public PublishOutcomeHandler(SnsClient snsClient, ParameterStoreUtil parameterStoreUtil) {
        this.snsClient = snsClient;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> result = new HashMap<>(input);
        Boolean allReserved = (Boolean) input.getOrDefault("allReserved", false);
        String status = Boolean.TRUE.equals(allReserved) ? WorkflowStatus.CONFIRMED.name() : WorkflowStatus.FAILED.name();

        String topicArn = parameterStoreUtil.getParameter("/cloudtrack/order-outcome-topic-arn", System.getenv("ORDER_OUTCOME_TOPIC_ARN"));

        try {
            Map<String, Object> payload = new HashMap<>(input);
            payload.put("finalStatus", status);
            String messageBody = objectMapper.writeValueAsString(payload);

            // Set SNS Message Attributes for subscription filter policy (status = CONFIRMED | FAILED)
            Map<String, MessageAttributeValue> messageAttributes = Map.of(
                    "status", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(status)
                            .build()
            );

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(messageBody)
                    .messageAttributes(messageAttributes)
                    .build();

            PublishResponse publishResponse = snsClient.publish(publishRequest);
            result.put("snsMessageId", publishResponse.messageId());
            result.put("outcomePublished", true);
            context.getLogger().log("Published outcome for order: " + input.get("orderId") + " with status: " + status);

        } catch (Exception e) {
            context.getLogger().log("Error publishing SNS outcome: " + e.getMessage());
            result.put("outcomePublished", false);
            result.put("snsError", e.getMessage());
        }

        result.put("calculatedStatus", status);
        return result;
    }
}
