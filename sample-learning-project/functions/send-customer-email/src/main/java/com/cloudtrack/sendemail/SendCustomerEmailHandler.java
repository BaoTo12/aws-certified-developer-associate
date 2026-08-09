package com.cloudtrack.sendemail;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.cloudtrack.common.util.ParameterStoreUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.ArrayList;
import java.util.List;

public class SendCustomerEmailHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final SesClient sesClient;
    private final ParameterStoreUtil parameterStoreUtil;
    private final ObjectMapper objectMapper;

    public SendCustomerEmailHandler() {
        this.sesClient = SesClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
        this.objectMapper = new ObjectMapper();
    }

    public SendCustomerEmailHandler(SesClient sesClient, ParameterStoreUtil parameterStoreUtil) {
        this.sesClient = sesClient;
        this.parameterStoreUtil = parameterStoreUtil;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> batchItemFailures = new ArrayList<>();
        String senderEmail = parameterStoreUtil.getParameter("/cloudtrack/sender-email", System.getenv("SENDER_EMAIL"));
        if (senderEmail == null || senderEmail.isBlank()) {
            senderEmail = "noreply@cloudtrack.local";
        }

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                // Parse SNS envelope or direct message body
                JsonNode bodyNode = objectMapper.readTree(msg.getBody());
                JsonNode payloadNode = bodyNode.has("Message") ? objectMapper.readTree(bodyNode.get("Message").asText()) : bodyNode;

                String orderId = payloadNode.has("orderId") ? payloadNode.get("orderId").asText() : "N/A";
                String customerId = payloadNode.has("customerId") ? payloadNode.get("customerId").asText() : "N/A";
                String finalStatus = payloadNode.has("finalStatus") ? payloadNode.get("finalStatus").asText() : "UPDATED";
                String recipientEmail = payloadNode.has("customerEmail") ? payloadNode.get("customerEmail").asText() : null;

                if (recipientEmail == null || recipientEmail.isBlank()) {
                    context.getLogger().log("No recipient email present for order " + orderId + ", skipping SES call.");
                    continue;
                }

                String subject = "CloudTrack Order " + orderId + " - Status Update: " + finalStatus;
                String htmlBody = String.format("<html><body>" +
                        "<h2>CloudTrack Order Notification</h2>" +
                        "<p>Dear Customer (%s),</p>" +
                        "<p>Your order <strong>%s</strong> status is now <strong>%s</strong>.</p>" +
                        "<p>Thank you for choosing CloudTrack!</p>" +
                        "</body></html>", customerId, orderId, finalStatus);

                SendEmailRequest sendEmailRequest = SendEmailRequest.builder()
                        .source(senderEmail)
                        .destination(Destination.builder().toAddresses(recipientEmail).build())
                        .message(Message.builder()
                                .subject(Content.builder().data(subject).build())
                                .body(Body.builder().html(Content.builder().data(htmlBody).build()).build())
                                .build())
                        .build();

                SendEmailResponse sendEmailResponse = sesClient.sendEmail(sendEmailRequest);
                context.getLogger().log("SES email sent successfully. MessageId: " + sendEmailResponse.messageId());

            } catch (MessageRejectedException e) {
                context.getLogger().log("SES MessageRejected (Sandbox restriction or unverified recipient): " + e.getMessage());
                // In sandbox mode, unverified recipient fails cleanly without crashing the batch
            } catch (Exception e) {
                context.getLogger().log("Error sending email for message " + msg.getMessageId() + ": " + e.getMessage());
                batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(msg.getMessageId()));
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(batchItemFailures).build();
    }
}
