# CloudTrack — Serverless Order & Notification Platform
> **AWS Certified Developer - Associate (DVA-C02) Practice & Learning Reference Project**

CloudTrack is a multi-module serverless order-management application written in **Java 21** using **AWS SDK for Java v2**. It covers core AWS developer services (Lambda Function URLs, DynamoDB GSI/Streams/TTL/Batch/Conditional updates, SQS DLQs & Event Source Mappings, SNS Subscription Filter Policies, Step Functions Standard Workflows, EventBridge Scheduler, KMS client encryption, SSM Parameter Store, SES sandbox emails) and includes standalone SDK modules for short-lived expensive AWS services (Secrets Manager, API Gateway, KMS CMK, S3).

---

## 📁 Repository Structure

```
sample-learning-project/
├── pom.xml                                     # Parent POM (Java 21, AWS SDK BOM 2.25.x)
├── common/                                     # Data models, DTOs & AWS utilities (KMS, SSM)
│   └── src/main/java/com/cloudtrack/common/
├── functions/                                  # 12 Core Lambda Function modules
│   ├── create-order/                           # POST /orders (SigV4 Function URL, KMS Encrypt, DynamoDB Put, SQS Send)
│   ├── get-order/                              # GET /orders/{orderId} (DynamoDB Get, KMS Decrypt)
│   ├── list-orders/                            # GET /customers/{customerId}/orders (GSI Query + Pagination)
│   ├── get-order-status/                       # GET /orders/{orderId}/status (DescribeExecution)
│   ├── process-order/                          # SQS OrderQueue -> Step Functions StartExecution + SQS Batch Failure
│   ├── check-inventory/                        # Step Functions Task: DynamoDB BatchGetItem
│   ├── reserve-stock/                          # Step Functions Task: DynamoDB conditional UpdateItem
│   ├── rollback-reservations/                  # Step Functions Task: DynamoDB compensating UpdateItem
│   ├── publish-outcome/                        # Step Functions Task: SNS Publish with status attributes
│   ├── update-order-status/                    # Step Functions Task: DynamoDB UpdateItem + TTL
│   ├── send-customer-email/                    # SQS EmailQueue -> SES SendEmail
│   └── daily-inventory-report/                 # EventBridge Scheduler -> DynamoDB Scan -> SNS Publish
├── statemachine/
│   └── order-fulfillment.asl.json              # Step Functions ASL Definition
├── practice-only/                              # Standalone AWS SDK practice main programs (run & tear down)
│   └── src/main/java/com/cloudtrack/practice/
│       ├── SecretsManagerDemo.java             # Create, Get, Put, ForceDelete
│       ├── ApiGatewayDemo.java                 # Create HTTP API, Stage, Throttling, Delete
│       ├── KmsCmkDemo.java                     # Create CMK, Encrypt, Decrypt, ScheduleKeyDeletion
│       └── S3Demo.java                         # Create Bucket, Put, Get, Presigned URL, Delete
├── provisioning/                               # Code-based Java SDK provisioner (no CLI required)
│   └── src/main/java/com/cloudtrack/provisioning/
│       ├── Main.java                           # Orchestrates idempotent resource setup
│       ├── IamSetup.java
│       ├── DynamoDbSetup.java
│       ├── SqsSetup.java
│       ├── SnsSetup.java
│       ├── LambdaSetup.java
│       ├── StepFunctionsSetup.java
│       ├── SchedulerSetup.java
│       └── ParameterStoreSetup.java
├── deploy-notes.md                             # Step-by-step deployment reference
└── README.md
```

---

## 🚀 How to Build & Statically Verify

1. **Build all modules with Maven**:
   ```bash
   mvn clean package
   ```

2. **Run Java Infrastructure Provisioning**:
   ```bash
   mvn -pl provisioning exec:java -Dexec.mainClass="com.cloudtrack.provisioning.Main"
   ```

3. **Run Standalone Costly API Demos**:
   - **Secrets Manager Practice**:
     ```bash
     mvn -pl practice-only exec:java -Dexec.mainClass="com.cloudtrack.practice.SecretsManagerDemo"
     ```
   - **API Gateway Practice**:
     ```bash
     mvn -pl practice-only exec:java -Dexec.mainClass="com.cloudtrack.practice.ApiGatewayDemo"
     ```
   - **KMS CMK Practice**:
     ```bash
     mvn -pl practice-only exec:java -Dexec.mainClass="com.cloudtrack.practice.KmsCmkDemo"
     ```
   - **S3 & Presigned URLs Practice**:
     ```bash
     mvn -pl practice-only exec:java -Dexec.mainClass="com.cloudtrack.practice.S3Demo"
     ```
