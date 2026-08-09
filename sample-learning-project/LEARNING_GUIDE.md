# CloudTrack — DVA-C02 Master Study & Hands-On Learning Guide

Welcome to the **CloudTrack** study guide! This guide is designed to take you step-by-step from zero knowledge through every single component of this project. By following this roadmap, you will master the key concepts tested on the **AWS Certified Developer - Associate (DVA-C02)** exam while understanding how every line of code fits together.

---

## 🗺️ Learning Roadmap Overview

```
Phase 1: Foundations & Architecture (Understand the Big Picture)
   │
   ▼
Phase 2: Maven & Common Data Layer (Data Models, DTOs, KMS, SSM)
   │
   ▼
Phase 3: Deep Dive into Core Lambda Handlers (12 AWS APIs & Handlers)
   │
   ▼
Phase 4: Workflow Orchestration with Step Functions (ASL State Machine)
   │
   ▼
Phase 5: Practice-Only Modules (Secrets Manager, API Gateway, KMS CMK, S3)
   │
   ▼
Phase 6: Infrastructure as Code via Java SDK (Provisioning Automation)
   │
   ▼
Phase 7: DVA-C02 Exam Domain Mapping & High-Yield Key Points
```

---

## 📌 Phase 1: Understand Project Architecture & DVA-C02 Philosophy

Before looking at any code, understand **why** CloudTrack is architected this way:

1. **Serverless & Event-Driven Architecture**:
   - Modern AWS applications decouple services using events (SQS queues and SNS topics) rather than synchronous HTTP calls between microservices.
   - When a client creates an order, `CreateOrder` quickly stores the order in DynamoDB and drops a message into `OrderQueue`, returning immediately (`201 Created`). The heavy lifting happens asynchronously.

2. **Always-Free Tier Focus**:
   - Live core components (Lambda, DynamoDB, SQS, SNS, Step Functions, EventBridge Scheduler) use AWS services that are 100% free at low volume or permanent Always-Free limits.
   - Costly components (API Gateway, Secrets Manager, KMS CMK, S3) are written in `practice-only/` classes designed to run once and tear down immediately.

### Key Files to Review First:
- [DVA-C02-Practice-Project.md](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/docs/DVA-C02-Practice-Project.md)
- [README.md](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/README.md)
- [deploy-notes.md](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/deploy-notes.md)

---

## 📌 Phase 2: Master Common Data Models & Helper Utilities

Start your code inspection in `common/`. This module establishes how data moves through the application and how AWS helper SDK clients work.

### 1. Data Models & DTOs
- **[Order.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/common/src/main/java/com/cloudtrack/common/model/Order.java)**: Represents an order in DynamoDB. Note the fields: `orderId` (Partition Key), `customerId` (GSI Partition Key), `createdAt` (GSI Sort Key), `customerEmail` (Encrypted), `status`, `executionArn`, and `expiresAt` (DynamoDB TTL attribute).
- **[WorkflowStatus.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/common/src/main/java/com/cloudtrack/common/model/WorkflowStatus.java)**: Enums `PENDING`, `IN_PROGRESS`, `CONFIRMED`, `FAILED`.

### 2. AWS SDK v2 Utilities
- **[KmsUtil.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/common/src/main/java/com/cloudtrack/common/util/KmsUtil.java)**:
  - **DVA-C02 Concept**: Envelope Encryption & AWS KMS API.
  - Study `kmsClient.encrypt(...)` and `kmsClient.decrypt(...)`. Notice how raw bytes are passed as `SdkBytes` and ciphertexts are Base64 encoded before saving to DynamoDB.
- **[ParameterStoreUtil.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/common/src/main/java/com/cloudtrack/common/util/ParameterStoreUtil.java)**:
  - **DVA-C02 Concept**: Centralized Configuration Management with AWS SSM Parameter Store.
  - Study `ssmClient.getParameter(...)` with `.withDecryption(true)` for secure strings.

---

## 📌 Phase 3: Learn the 12 Core Lambda Handlers & AWS Integration Patterns

Study each Lambda function in `functions/` in logical order of an order's lifecycle.

### Step 3.1: Order Creation Endpoint
- **[CreateOrderHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/create-order/src/main/java/com/cloudtrack/createorder/CreateOrderHandler.java)**
  - **Trigger**: Lambda Function URL (`APIGatewayV2HTTPEvent`).
  - **Exam Topics**:
    1. **Idempotency**: DynamoDB `putItem` with `.conditionExpression("attribute_not_exists(orderId)")`. Catches `ConditionalCheckFailedException` (returns `409 Conflict`).
    2. **Field-Level Encryption**: `kmsUtil.encrypt(request.getCustomerEmail())`.
    3. **Asynchronous Hand-off**: `sqsClient.sendMessage(...)` to `OrderQueue`.

### Step 3.2: Querying Orders & Pagination
- **[GetOrderHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/get-order/src/main/java/com/cloudtrack/getorder/GetOrderHandler.java)**: Demonstrates `getItem` and decrypting stored data with KMS.
- **[ListOrdersHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/list-orders/src/main/java/com/cloudtrack/listorders/ListOrdersHandler.java)**
  - **Exam Topics**:
    1. **DynamoDB GSI Query**: Querying `CustomerIndex` with `customerId = :c`.
    2. **Pagination Mechanics**: DynamoDB returns `lastEvaluatedKey`. Convert it to a Base64 string (`nextToken`) for the client. Pass `exclusiveStartKey` on subsequent requests.

### Step 3.3: Queue Processing & Partial Batch Failures
- **[ProcessOrderHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/process-order/src/main/java/com/cloudtrack/processorder/ProcessOrderHandler.java)**
  - **Trigger**: SQS Event Source Mapping (`SQSEvent`).
  - **Exam Topics**:
    1. **Step Functions Invocation**: `sfnClient.startExecution(...)`.
    2. **Partial Batch Failures**: `SQSBatchResponse` returning `batchItemFailures`. In DVA-C02, returning specific failed message IDs prevents SQS from re-processing items in the batch that already succeeded!

### Step 3.4: State Machine Workers & Compensating Transactions
- **[CheckInventoryHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/check-inventory/src/main/java/com/cloudtrack/checkinventory/CheckInventoryHandler.java)**: Demonstrates `dynamodb:BatchGetItem` across multiple line items in a single HTTP round-trip.
- **[ReserveStockHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/reserve-stock/src/main/java/com/cloudtrack/reservestock/ReserveStockHandler.java)**: Demonstrates `updateItem` with `stock >= :qty` condition expression per item.
- **[RollbackReservationsHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/rollback-reservations/src/main/java/com/cloudtrack/rollbackreservations/RollbackReservationsHandler.java)**: Implements **Compensating Transactions** (Saga Pattern) to add back stock if any item in an order fails reservation.
- **[PublishOutcomeHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/publish-outcome/src/main/java/com/cloudtrack/publishoutcome/PublishOutcomeHandler.java)**: Demonstrates SNS `Publish` with `MessageAttributeValue` (`status=CONFIRMED|FAILED`).
- **[UpdateOrderStatusHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/update-order-status/src/main/java/com/cloudtrack/updateorderstatus/UpdateOrderStatusHandler.java)**: Updates DynamoDB order status and sets `expiresAt` (DynamoDB Time-To-Live).

### Step 3.5: Filtered SNS Subscriptions & Scheduled Reports
- **[SendCustomerEmailHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/send-customer-email/src/main/java/com/cloudtrack/sendemail/SendCustomerEmailHandler.java)**: Receives messages from `EmailQueue` (fed by SNS filter policy) and calls SES `SendEmail`.
- **[DailyInventoryReportHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/daily-inventory-report/src/main/java/com/cloudtrack/dailyreport/DailyInventoryReportHandler.java)**: Triggered by EventBridge Scheduler (`rate(1 day)`), executes DynamoDB `Scan`.

---

## 📌 Phase 4: Understand Step Functions & ASL State Machines

Study [order-fulfillment.asl.json](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/statemachine/order-fulfillment.asl.json).

### Key State Machine Concepts:
1. **States**: `CheckInventory` (Task) → `ReserveStock` (Task) → `ChoiceAllReserved` (Choice) → [`RollbackReservations`] → `PublishOutcome` → `UpdateOrderStatus`.
2. **Standard vs. Express Workflows**: Standard workflows support long-running processes, exact once execution, and detailed visual inspection in CloudWatch/X-Ray.
3. **Retry & Backoff**: Notice `Retry` blocks in ASL with `IntervalSeconds`, `MaxAttempts`, and `BackoffRate`.

---

## 📌 Phase 5: Study Practice-Only Costly AWS APIs

Inspect the standalone runnable main classes in `practice-only/`:

1. **[SecretsManagerDemo.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/practice-only/src/main/java/com/cloudtrack/practice/SecretsManagerDemo.java)**:
   - Learn the difference between SSM Parameter Store (free standard tier) and Secrets Manager (cost per secret, automatic rotation).
   - Study `createSecret`, `getSecretValue`, `putSecretValue`, and `deleteSecret` with `forceDeleteWithoutRecovery(true)`.

2. **[ApiGatewayDemo.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/practice-only/src/main/java/com/cloudtrack/practice/ApiGatewayDemo.java)**:
   - Learn how API Gateway HTTP APIs manage routes, stages, throttling (`throttlingBurstLimit`, `throttlingRateLimit`), and authorizers.

3. **[KmsCmkDemo.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/practice-only/src/main/java/com/cloudtrack/practice/KmsCmkDemo.java)**:
   - Learn the difference between AWS-managed keys (`alias/aws/dynamodb`) and Customer Managed Keys (CMKs). Notice `scheduleKeyDeletion` requires a minimum 7-day waiting period.

4. **[S3Demo.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/practice-only/src/main/java/com/cloudtrack/practice/S3Demo.java)**:
   - Learn object storage APIs (`putObject`, `getObject`), batch deletion (`deleteObjects`), and generating **Presigned URLs** for temporary, secure user downloads using `S3Presigner`.

---

## 📌 Phase 6: Learn Infrastructure Provisioning Automation (SDK vs CLI)

Study `provisioning/`:
- **[Main.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/provisioning/src/main/java/com/cloudtrack/provisioning/Main.java)**: Shows how real production deployment tools automate infrastructure using AWS SDK v2.
- **Key Provisioning Classes**:
  - `IamSetup`: Creating trust policies and attaching IAM roles.
  - `DynamoDbSetup`: Programmatic table creation with GSIs and Streams.
  - `SqsSetup` & `SnsSetup`: Configuring queues with DLQs and SNS subscription filter policies.
  - `LambdaSetup`: Deploying compiled Java JARs, configuring Function URLs, and creating Event Source Mappings.
  - `StepFunctionsSetup`: Deploying ASL state machines.
  - `SchedulerSetup`: Setting up EventBridge rate schedules.

---

## 🎓 Phase 7: DVA-C02 High-Yield Exam Cheat Sheet

| Topic | AWS API / Concept | Exam Shortcut / Key Rule | CloudTrack Implementation |
|---|---|---|---|
| **DynamoDB Idempotency** | `putItem` condition expression | Use `attribute_not_exists(key)` to prevent duplicate overwrites on client retries | [CreateOrderHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/create-order/src/main/java/com/cloudtrack/createorder/CreateOrderHandler.java) |
| **DynamoDB Query vs Scan** | `Query` on GSI | `Query` fetches by Partition Key; `Scan` reads the whole table (use Scan only for background batch reports) | [ListOrdersHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/list-orders/src/main/java/com/cloudtrack/listorders/ListOrdersHandler.java) |
| **DynamoDB Pagination** | `LastEvaluatedKey` / `ExclusiveStartKey` | Pass `ExclusiveStartKey` on next query to fetch subsequent pages | [ListOrdersHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/list-orders/src/main/java/com/cloudtrack/listorders/ListOrdersHandler.java) |
| **SQS Partial Batch Failures** | `SQSBatchResponse` | Enable `ReportBatchItemFailures` so failed messages don't force retry of successful ones | [ProcessOrderHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/process-order/src/main/java/com/cloudtrack/processorder/ProcessOrderHandler.java) |
| **SQS DLQ Redrive** | `RedrivePolicy` | Messages failing > `maxReceiveCount` automatically move to DLQ | [SqsSetup.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/provisioning/src/main/java/com/cloudtrack/provisioning/SqsSetup.java) |
| **SNS Message Filtering** | `FilterPolicy` | SNS evaluates message attributes; subscriber receives only matching messages | [SnsSetup.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/provisioning/src/main/java/com/cloudtrack/provisioning/SnsSetup.java) & [PublishOutcomeHandler.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/functions/publish-outcome/src/main/java/com/cloudtrack/publishoutcome/PublishOutcomeHandler.java) |
| **Saga / Rollback Pattern** | Step Functions Choice & Tasks | Use compensating transactions (Rollback lambda) to undo state changes on failure | [order-fulfillment.asl.json](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/statemachine/order-fulfillment.asl.json) |
| **KMS Encryption** | `kms:Encrypt` / `kms:Decrypt` | Envelope encryption using AWS-managed key (`alias/aws/dynamodb`) is $0/mo | [KmsUtil.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/common/src/main/java/com/cloudtrack/common/util/KmsUtil.java) |
| **S3 Presigned URLs** | `S3Presigner` | Grants temporary time-limited access to private S3 objects without changing IAM permissions | [S3Demo.java](file:///C:/Users/Admin/Desktop/projects/aws-certified-developer-associate/sample-learning-project/practice-only/src/main/java/com/cloudtrack/practice/S3Demo.java) |
