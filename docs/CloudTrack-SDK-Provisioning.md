# CloudTrack — Provisioning by Code (AWS SDK for Java, no CLI)

## Is this possible?

Yes. Every `aws <service> create-*` CLI command in the implementation guide has a 1:1 equivalent SDK call — `aws dynamodb create-table` is `DynamoDbClient.createTable(...)`, `aws lambda create-function` is `LambdaClient.createFunction(...)`, and so on. The CLI is just a thin wrapper around the same API. Writing it in Java instead means you're practicing the exact SDK client classes and request/response builders the exam (and real jobs) actually test — arguably more useful than the CLI for your purposes.

## How to structure it

Keep this **separate from your Lambda function code** — it's a one-time setup program, not something that runs in Lambda. A clean layout:

```
cloudtrack/
├── functions/              # your Lambda handlers (unchanged)
├── provisioning/           # NEW — a plain Java app, run locally with `mvn exec:java`
│   ├── src/main/java/com/cloudtrack/provisioning/
│   │   ├── Main.java             # runs each step in order, prints ARNs as it goes
│   │   ├── IamSetup.java
│   │   ├── DynamoDbSetup.java
│   │   ├── SqsSetup.java
│   │   ├── SnsSetup.java
│   │   ├── LambdaSetup.java
│   │   ├── StepFunctionsSetup.java
│   │   ├── SchedulerSetup.java
│   │   └── ParameterStoreSetup.java
│   └── pom.xml
```

`Main.java` calls each `*Setup` class's `create()` method in the same order as the build checklist (IAM → Parameter Store → DynamoDB → SQS → SNS → Lambda → Step Functions → Scheduler), and passes ARNs/URLs from earlier steps into later ones instead of you copy-pasting them between terminal commands.

**Dependencies (add to `provisioning/pom.xml`):** the same `software.amazon.awssdk:bom` you're already using in the function modules, plus these SDK clients: `iam`, `ssm`, `dynamodb`, `sqs`, `sns`, `lambda`, `sfn` (Step Functions), `scheduler`.

**Make it idempotent:** check-then-create, so you can re-run `Main.java` safely while you're iterating:
```java
boolean exists;
try {
    dynamoDb.describeTable(r -> r.tableName("Orders"));
    exists = true;
} catch (ResourceNotFoundException e) {
    exists = false;
}
if (!exists) {
    dynamoDb.createTable(/* ... */);
}
```

---

## Worked examples, one per resource type

### IAM role + inline policy
```java
IamClient iam = IamClient.builder().build();

String trustPolicy = """
    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
     "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

CreateRoleResponse role = iam.createRole(r -> r
    .roleName("CreateOrderRole")
    .assumeRolePolicyDocument(trustPolicy));

String policy = """
    {"Version":"2012-10-17","Statement":[
      {"Effect":"Allow","Action":"dynamodb:PutItem","Resource":"arn:aws:dynamodb:*:*:table/Orders"},
      {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"arn:aws:sqs:*:*:OrderQueue"},
      {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],"Resource":"arn:aws:logs:*:*:*"}
    ]}""";

iam.putRolePolicy(r -> r
    .roleName("CreateOrderRole")
    .policyName("CreateOrderPolicy")
    .policyDocument(policy));

String roleArn = role.role().arn();   // pass this into LambdaSetup
```
IAM roles take a few seconds to propagate — if `createFunction` right after this fails with "role cannot be assumed," add a short retry loop or a `Thread.sleep(8000)` before moving on; this is a real, commonly-hit timing quirk worth knowing for the exam.

### DynamoDB table with a GSI and a stream
```java
DynamoDbClient ddb = DynamoDbClient.create();

ddb.createTable(r -> r
    .tableName("Orders")
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
        .streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES).build()));
```

### SQS queue with a DLQ redrive policy
```java
SqsClient sqs = SqsClient.create();

String dlqUrl = sqs.createQueue(r -> r.queueName("OrderQueueDLQ")).queueUrl();
String dlqArn = sqs.getQueueAttributes(r -> r.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
    .attributes().get(QueueAttributeName.QUEUE_ARN);

Map<QueueAttributeName, String> attrs = Map.of(
    QueueAttributeName.REDRIVE_POLICY,
    "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}",
    QueueAttributeName.VISIBILITY_TIMEOUT, "60");

String orderQueueUrl = sqs.createQueue(r -> r.queueName("OrderQueue").attributes(attrs)).queueUrl();
```

### SNS topic + filtered subscription
```java
SnsClient sns = SnsClient.create();

String topicArn = sns.createTopic(r -> r.name("OrderOutcome")).topicArn();

String subArn = sns.subscribe(r -> r
    .topicArn(topicArn).protocol("sqs").endpoint(emailQueueArn)).subscriptionArn();

sns.setSubscriptionAttributes(r -> r
    .subscriptionArn(subArn)
    .attributeName("FilterPolicy")
    .attributeValue("{\"status\":[\"CONFIRMED\",\"FAILED\"]}"));
```

### Lambda function — deploying your built jar
This is the one place code-based provisioning needs something the CLI also needs: the packaged jar from `mvn package`. Read it as bytes and hand it to `CreateFunctionRequest`:
```java
LambdaClient lambda = LambdaClient.create();

byte[] jarBytes = Files.readAllBytes(Path.of("functions/create-order/target/create-order.jar"));

lambda.createFunction(r -> r
    .functionName("CreateOrder")
    .runtime(Runtime.JAVA21)
    .role(roleArn)
    .handler("com.cloudtrack.createorder.Handler::handleRequest")
    .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(jarBytes)).build())
    .timeout(15).memorySize(512)
    .tracingConfig(TracingConfig.builder().mode(TracingMode.ACTIVE).build()));

lambda.createFunctionUrlConfig(r -> r.functionName("CreateOrder").authType(FunctionUrlAuthType.AWS_IAM));

lambda.addPermission(r -> r
    .functionName("CreateOrder")
    .action("lambda:InvokeFunctionUrl")
    .statementId("AllowMyIamUser")
    .principal("arn:aws:iam::" + accountId + ":user/YOUR_IAM_USER")
    .functionUrlAuthType(FunctionUrlAuthType.AWS_IAM));
```

### Step Functions state machine
```java
SfnClient sfn = SfnClient.create();

String asl = Files.readString(Path.of("statemachine/order-fulfillment.asl.json"));

String stateMachineArn = sfn.createStateMachine(r -> r
    .name("OrderFulfillment")
    .definition(asl)
    .roleArn(stepFunctionsRoleArn)
    .type(StateMachineType.STANDARD)
    .tracingConfiguration(TracingConfiguration.builder().enabled(true).build()))
    .stateMachineArn();
```

### EventBridge Scheduler — daily report
```java
SchedulerClient scheduler = SchedulerClient.create();

scheduler.createSchedule(r -> r
    .name("daily-inventory-report")
    .scheduleExpression("rate(1 day)")
    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
    .target(Target.builder()
        .arn("arn:aws:lambda:" + region + ":" + accountId + ":function:DailyInventoryReport")
        .roleArn(schedulerRoleArn)
        .build()));
```

### Parameter Store — feeding real values back in
```java
SsmClient ssm = SsmClient.create();

ssm.putParameter(r -> r
    .name("/cloudtrack/order-queue-url").type(ParameterType.STRING)
    .value(orderQueueUrl).overwrite(true));
```

---

## Wiring `Main.java`

```java
public class Main {
    public static void main(String[] args) {
        String roleArn = IamSetup.create();
        DynamoDbSetup.create();
        String orderQueueUrl = SqsSetup.create();
        String topicArn = SnsSetup.create();
        LambdaSetup.create(roleArn);
        String stateMachineArn = StepFunctionsSetup.create();
        SchedulerSetup.create();
        ParameterStoreSetup.save(orderQueueUrl, topicArn, stateMachineArn);
        System.out.println("Provisioning complete.");
    }
}
```
Run it with `mvn -pl provisioning exec:java -Dexec.mainClass=com.cloudtrack.provisioning.Main` from the repo root (build the function jars first — `mvn clean package` — since `LambdaSetup` reads them off disk).

---

## What this replaces vs. what it doesn't

Everything in §3–11 of the implementation guide (Parameter Store, DynamoDB, SQS, SNS, Lambda, Step Functions, EventBridge Scheduler) maps cleanly to SDK calls as shown above. Two things stay manual regardless of CLI vs. code:
- **SES sender/recipient verification** — it's a click-the-link-in-your-email step either way
- **Postman testing** — that's exercising the *finished* API, not provisioning it

---

*Want the full, runnable `provisioning/` module written out (all `*Setup.java` classes, complete, not just the excerpts above)?*
