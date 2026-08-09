package com.cloudtrack.dailyreport;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.cloudtrack.common.util.ParameterStoreUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.util.Map;

public class DailyInventoryReportHandler implements RequestHandler<Map<String, Object>, String> {

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final ParameterStoreUtil parameterStoreUtil;

    public DailyInventoryReportHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        SsmClient ssmClient = SsmClient.create();
        this.parameterStoreUtil = new ParameterStoreUtil(ssmClient);
    }

    public DailyInventoryReportHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient, ParameterStoreUtil parameterStoreUtil) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.parameterStoreUtil = parameterStoreUtil;
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        String inventoryTableName = parameterStoreUtil.getParameter("/cloudtrack/inventory-table-name", "Inventory");
        String topicArn = parameterStoreUtil.getParameter("/cloudtrack/order-outcome-topic-arn", System.getenv("ORDER_OUTCOME_TOPIC_ARN"));

        try {
            // Perform DynamoDB Scan (acceptable for small background daily batch report)
            ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(inventoryTableName)
                    .build());

            int totalSkus = scanResponse.count();
            int totalStock = 0;
            int lowStockCount = 0;

            StringBuilder reportBuilder = new StringBuilder();
            reportBuilder.append("=== CloudTrack Daily Inventory Report (").append(Instant.now()).append(") ===\n\n");
            reportBuilder.append(String.format("%-15s | %-10s | %s\n", "SKU", "Stock", "Description"));
            reportBuilder.append("--------------------------------------------------\n");

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                String sku = item.containsKey("sku") ? item.get("sku").s() : "N/A";
                int stock = item.containsKey("stock") ? Integer.parseInt(item.get("stock").n()) : 0;
                String desc = item.containsKey("description") ? item.get("description").s() : "";

                totalStock += stock;
                if (stock < 5) {
                    lowStockCount++;
                }

                reportBuilder.append(String.format("%-15s | %-10d | %s\n", sku, stock, desc));
            }

            reportBuilder.append("\nSummary:\n");
            reportBuilder.append("Total SKUs: ").append(totalSkus).append("\n");
            reportBuilder.append("Total Stock: ").append(totalStock).append("\n");
            reportBuilder.append("Low Stock SKUs (< 5): ").append(lowStockCount).append("\n");

            String reportMessage = reportBuilder.toString();
            context.getLogger().log(reportMessage);

            if (topicArn != null && !topicArn.isBlank()) {
                snsClient.publish(PublishRequest.builder()
                        .topicArn(topicArn)
                        .subject("Daily Inventory Summary Report")
                        .message(reportMessage)
                        .build());
            }

            return "Daily inventory report completed successfully. Total SKUs: " + totalSkus;

        } catch (Exception e) {
            context.getLogger().log("Error generating daily inventory report: " + e.getMessage());
            return "Failed to generate report: " + e.getMessage();
        }
    }
}
