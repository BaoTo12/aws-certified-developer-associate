package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

public class DynamoDbSetup {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbSetup.class);
    private static final String TABLE_MSG_PREFIX = "[DynamoDB] Table ";

    private DynamoDbSetup() {
        // Utility class private constructor
    }

    public static void setupOrdersTable(DynamoDbClient ddb, String tableName) {
        try {
            ddb.describeTable(r -> r.tableName(tableName));
            logger.info("{}{} already exists.", TABLE_MSG_PREFIX, tableName);
            return;
        } catch (ResourceNotFoundException e) {
            logger.info("{}Creating table {}...", TABLE_MSG_PREFIX, tableName);
        }

        ddb.createTable(r -> r
                .tableName(tableName)
                .attributeDefinitions(
                        a -> a.attributeName("orderId").attributeType(ScalarAttributeType.S),
                        a -> a.attributeName("customerId").attributeType(ScalarAttributeType.S),
                        a -> a.attributeName("createdAt").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("orderId").keyType(KeyType.HASH))
                .globalSecondaryIndexes(gsi -> gsi
                        .indexName("CustomerIndex")
                        .keySchema(
                                k -> k.attributeName("customerId").keyType(KeyType.HASH),
                                k -> k.attributeName("createdAt").keyType(KeyType.RANGE))
                        .projection(p -> p.projectionType(ProjectionType.ALL))
                        .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L)))
                .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L))
                .streamSpecification(s -> s.streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)));

        logger.info("{}{} creation initiated.", TABLE_MSG_PREFIX, tableName);
    }

    public static void setupInventoryTable(DynamoDbClient ddb, String tableName) {
        try {
            ddb.describeTable(r -> r.tableName(tableName));
            logger.info("{}{} already exists.", TABLE_MSG_PREFIX, tableName);
            return;
        } catch (ResourceNotFoundException e) {
            logger.info("{}Creating table {}...", TABLE_MSG_PREFIX, tableName);
        }

        ddb.createTable(r -> r
                .tableName(tableName)
                .attributeDefinitions(a -> a.attributeName("sku").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("sku").keyType(KeyType.HASH))
                .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L)));

        logger.info("{}{} creation initiated.", TABLE_MSG_PREFIX, tableName);
    }
}
