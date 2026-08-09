package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class DynamoDbSetup {

    public static void setupOrdersTable(DynamoDbClient ddb, String tableName) {
        try {
            ddb.describeTable(r -> r.tableName(tableName));
            System.out.println("[DynamoDB] Table " + tableName + " already exists.");
            return;
        } catch (ResourceNotFoundException e) {
            System.out.println("[DynamoDB] Creating table " + tableName + "...");
        }

        ddb.createTable(r -> r
                .tableName(tableName)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("customerId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("createdAt").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("CustomerIndex")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build())
                        .build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build())
                .streamSpecification(StreamSpecification.builder()
                        .streamEnabled(true)
                        .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                        .build()));

        System.out.println("[DynamoDB] Table " + tableName + " creation initiated.");
    }

    public static void setupInventoryTable(DynamoDbClient ddb, String tableName) {
        try {
            ddb.describeTable(r -> r.tableName(tableName));
            System.out.println("[DynamoDB] Table " + tableName + " already exists.");
            return;
        } catch (ResourceNotFoundException e) {
            System.out.println("[DynamoDB] Creating table " + tableName + "...");
        }

        ddb.createTable(r -> r
                .tableName(tableName)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("sku").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("sku").keyType(KeyType.HASH).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build()));

        System.out.println("[DynamoDB] Table " + tableName + " creation initiated.");
    }
}
