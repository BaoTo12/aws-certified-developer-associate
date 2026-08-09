# CloudTrack — Deployment Notes & Reference Guide

This document summarizes the exact steps and AWS SDK commands used to build, provision, and verify the CloudTrack Serverless Order & Notification Platform.

---

## 1. Project Architecture Summary

```
Client (Postman / SigV4)
  │
  ├─► POST /orders (CreateOrder URL) ──► KMS Encrypt email ──► DynamoDB PutItem (Orders) ──► SQS SendMessage (OrderQueue)
  ├─► GET /orders/{orderId} (GetOrder URL) ──► DynamoDB GetItem ──► KMS Decrypt email
  ├─► GET /customers/{customerId}/orders (ListOrders URL) ──► DynamoDB Query (CustomerIndex GSI + Base64 nextToken)
  └─► GET /orders/{orderId}/status (GetOrderStatus URL) ──► DynamoDB GetItem ──► Step Functions DescribeExecution

SQS: OrderQueue ──► Lambda: ProcessOrder ──► Step Functions: OrderFulfillment (Standard Workflow)
                                                   ├─ CheckInventory (BatchGetItem)
                                                   ├─ ReserveStock (Conditional UpdateItem per item)
                                                   ├─ Choice (allReserved?)
                                                   │     ├─ No ──► RollbackReservations (Compensating UpdateItem)
                                                   │     └─ (Both) ──► PublishOutcome (SNS Publish + status attribute)
                                                   └─ UpdateOrderStatus (UpdateItem + TTL expiresAt)

SNS: OrderOutcome ──► Subscription Filter (status=CONFIRMED|FAILED) ──► SQS: EmailQueue ──► Lambda: SendCustomerEmail ──► SES SendEmail

EventBridge Scheduler (rate(1 day)) ──► Lambda: DailyInventoryReport ──► DynamoDB Scan ──► SNS Publish
```

---

## 2. Java SDK Code-Based Provisioning

Infrastructure is provisioned programmatically in Java using AWS SDK v2 (`provisioning/` module), bypassing manual CLI steps.

### Execution Command:
```bash
mvn clean package
mvn -pl provisioning exec:java -Dexec.mainClass="com.cloudtrack.provisioning.Main"
```

### Order of Provisioning:
1. **IAM Roles**: `CloudTrackLambdaRole` & `CloudTrackStepFunctionsRole` with trust and invocation policies.
2. **DynamoDB Tables**: `Orders` (partition key `orderId`, GSI `CustomerIndex`, stream `NEW_AND_OLD_IMAGES`) & `Inventory` (partition key `sku`).
3. **SQS Queues**: `OrderQueue` (+ `OrderQueueDLQ`) and `EmailQueue` (+ `EmailQueueDLQ`) with redrive policy (`maxReceiveCount=3`).
4. **SNS Topic & Subscription**: `OrderOutcome` topic with SQS subscription filter policy (`{"status":["CONFIRMED","FAILED"]}`).
5. **Lambda Functions & URLs**: 12 Lambda functions with IAM auth Function URLs for public endpoints and Event Source Mappings for SQS triggers.
6. **Step Functions**: `OrderFulfillment` standard state machine parsed from `statemachine/order-fulfillment.asl.json`.
7. **EventBridge Scheduler**: `daily-inventory-report` schedule executing `rate(1 day)`.
8. **SSM Parameter Store**: Stores `/cloudtrack/*` parameters (`/cloudtrack/orders-table-name`, `/cloudtrack/order-queue-url`, etc.).

---

## 3. Practice-Only Modules (Run Once & Tear Down)

To practice expensive DVA-C02 APIs without ongoing monthly charges:

### 1. Secrets Manager (`practice-only/src/main/java/com/cloudtrack/practice/SecretsManagerDemo.java`)
- `CreateSecret` → `GetSecretValue` → `PutSecretValue` → `DeleteSecret(ForceDeleteWithoutRecovery=true)`

### 2. Amazon API Gateway (`practice-only/src/main/java/com/cloudtrack/practice/ApiGatewayDemo.java`)
- `CreateApi` (HTTP API) → `CreateStage` ($default with throttling) → `CreateRoute` → `DeleteApi`

### 3. AWS KMS Customer Managed Key (`practice-only/src/main/java/com/cloudtrack/practice/KmsCmkDemo.java`)
- `CreateKey` (CMK) → `Encrypt` → `Decrypt` → `ScheduleKeyDeletion(7 days)`

### 4. Amazon S3 (`practice-only/src/main/java/com/cloudtrack/practice/S3Demo.java`)
- `CreateBucket` → `PutObject` → `GetObject` → `GeneratePresignedUrl` → `ListObjectsV2` → `DeleteObjects` → `DeleteBucket`
