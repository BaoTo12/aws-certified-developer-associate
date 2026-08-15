package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String SEPARATOR = "=================================================";

    public static void main(String[] args) {
        logger.info(SEPARATOR);
        logger.info("  CloudTrack AWS Infrastructure SDK Provisioner ");
        logger.info(SEPARATOR);

        // context: in aws every API call to aws services such as S3, Lambda the requests must be signed cryptographically with AWS Access Key ID, Secret Access Key
        // --> AwsCredentialsProvider provides AwsCredentials whenever the SDK needs to authenticate an outbound HTTP request to AWS.
        // How this retrieves our access key and secret access key
        /*
         * SystemPropertyCredentialsProvider: Reads credentials from Java system properties
         * EnvironmentVariableCredentialsProvider: Reads environment variables
         * ProfileCredentialsProvider: Reads credentials from the local shared AWS credentials file located at ~/.aws/credentials (commonly created via aws configure).
         * WebIdentityTokenFileCredentialsProvider: Used in Kubernetes (EKS) or OpenID Connect (OIDC) setups to exchange web identity tokens for temporary AWS IAM role credentials.
         * ContainerCredentialsProvider: Queries the local ECS/Fargate task agent endpoint to get temporary credentials assigned to an ECS Task Role.
         * InstanceProfileCredentialsProvider: Queries the EC2 Instance Metadata Service (IMDSv2) to retrieve temporary credentials attached to an IAM Instance Profile.
         * */
        // ? AwsCredentialsProvider. It uses a design pattern called a Credential Provider Chain
        /*
        * [ Your Application Call ]
           │
           ▼
            1. SystemPropertyCredentialsProvider
               └── Checks: -Daws.accessKeyId & -Daws.secretAccessKey
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            2. EnvironmentVariableCredentialsProvider
               └── Checks: AWS_ACCESS_KEY_ID & AWS_SECRET_ACCESS_KEY
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            3. WebIdentityTokenFileCredentialsProvider
               └── Checks: AWS_WEB_IDENTITY_TOKEN_FILE (EKS Pod Identity / OIDC)
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            4. ProfileCredentialsProvider
               └── Checks: ~/.aws/credentials (default profile or AWS_PROFILE)
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            5. ContainerCredentialsProvider
               └── Checks: AWS_CONTAINER_CREDENTIALS_RELATIVE_URI (ECS / Fargate)
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            6. InstanceProfileCredentialsProvider
               └── Checks: http://169.254.169.254/latest/meta-data/ (EC2 IMDSv2)
               └── Found? ──► [ YES ] ──► Return Credentials & Stop
                       │ [ NO ]
                       ▼
            [ Throw SdkClientException: Unable to load credentials ]
        *
        * */
        Region awsRegion = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

        try (IamClient iamClient = IamClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             SqsClient sqsClient = SqsClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             SnsClient snsClient = SnsClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             LambdaClient lambdaClient = LambdaClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             SfnClient sfnClient = SfnClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build(); // AWS Step Functions
             SchedulerClient schedulerClient = SchedulerClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();
             SsmClient ssmClient = SsmClient.builder().region(awsRegion).credentialsProvider(credentialsProvider).build() // AWS Systems Manager - specifically Parameter Store
        ) {

            // 1. IAM Roles
            logger.info("\n--- Step 1: Setting up IAM Roles ---");
            String lambdaRoleArn = IamSetup.setupLambdaRole(iamClient, "CloudTrackLambdaRole");
            String sfnRoleArn = IamSetup.setupStepFunctionsRole(iamClient, "CloudTrackStepFunctionsRole");

            // 2. DynamoDB Tables
            logger.info("\n--- Step 2: Provisioning DynamoDB Tables ---");
            DynamoDbSetup.setupOrdersTable(dynamoDbClient, "Orders");
            DynamoDbSetup.setupInventoryTable(dynamoDbClient, "Inventory");

            // 3. SQS Queues
            logger.info("\n--- Step 3: Provisioning SQS Queues ---");
            String orderQueueUrl = SqsSetup.setupQueueWithDlq(sqsClient, "OrderQueue", "OrderQueueDLQ");
            String emailQueueUrl = SqsSetup.setupQueueWithDlq(sqsClient, "EmailQueue", "EmailQueueDLQ");
            String emailQueueArn = SqsSetup.getQueueArn(sqsClient, emailQueueUrl);
            String orderQueueArn = SqsSetup.getQueueArn(sqsClient, orderQueueUrl);

            // 4. SNS Topic
            logger.info("\n--- Step 4: Provisioning SNS Topic & Subscription ---");
            String topicArn = SnsSetup.setupTopicAndSubscription(snsClient, "OrderOutcome", emailQueueArn);

            // 5. Lambda Functions
            logger.info("\n--- Step 5: Provisioning Lambda Functions ---");
            LambdaSetup.createOrUpdateFunction(lambdaClient, "CreateOrder", "com.cloudtrack.createorder.CreateOrderHandler::handleRequest", lambdaRoleArn, "functions/create-order/target/create-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambdaClient, "CreateOrder");

            LambdaSetup.createOrUpdateFunction(lambdaClient, "GetOrder", "com.cloudtrack.getorder.GetOrderHandler::handleRequest", lambdaRoleArn, "functions/get-order/target/get-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambdaClient, "GetOrder");

            LambdaSetup.createOrUpdateFunction(lambdaClient, "ListOrders", "com.cloudtrack.listorders.ListOrdersHandler::handleRequest", lambdaRoleArn, "functions/list-orders/target/list-orders-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambdaClient, "ListOrders");

            LambdaSetup.createOrUpdateFunction(lambdaClient, "GetOrderStatus", "com.cloudtrack.getorderstatus.GetOrderStatusHandler::handleRequest", lambdaRoleArn, "functions/get-order-status/target/get-order-status-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambdaClient, "GetOrderStatus");

            LambdaSetup.createOrUpdateFunction(lambdaClient, "ProcessOrder", "com.cloudtrack.processorder.ProcessOrderHandler::handleRequest", lambdaRoleArn, "functions/process-order/target/process-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createSqsEventSourceMapping(lambdaClient, "ProcessOrder", orderQueueArn);

            LambdaSetup.createOrUpdateFunction(lambdaClient, "CheckInventory", "com.cloudtrack.checkinventory.CheckInventoryHandler::handleRequest", lambdaRoleArn, "functions/check-inventory/target/check-inventory-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambdaClient, "ReserveStock", "com.cloudtrack.reservestock.ReserveStockHandler::handleRequest", lambdaRoleArn, "functions/reserve-stock/target/reserve-stock-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambdaClient, "RollbackReservations", "com.cloudtrack.rollbackreservations.RollbackReservationsHandler::handleRequest", lambdaRoleArn, "functions/rollback-reservations/target/rollback-reservations-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambdaClient, "PublishOutcome", "com.cloudtrack.publishoutcome.PublishOutcomeHandler::handleRequest", lambdaRoleArn, "functions/publish-outcome/target/publish-outcome-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambdaClient, "UpdateOrderStatus", "com.cloudtrack.updateorderstatus.UpdateOrderStatusHandler::handleRequest", lambdaRoleArn, "functions/update-order-status/target/update-order-status-1.0-SNAPSHOT.jar");

            LambdaSetup.createOrUpdateFunction(lambdaClient, "SendCustomerEmail", "com.cloudtrack.sendemail.SendCustomerEmailHandler::handleRequest", lambdaRoleArn, "functions/send-customer-email/target/send-customer-email-1.0-SNAPSHOT.jar");
            LambdaSetup.createSqsEventSourceMapping(lambdaClient, "SendCustomerEmail", emailQueueArn);

            String dailyReportArn = LambdaSetup.createOrUpdateFunction(lambdaClient, "DailyInventoryReport", "com.cloudtrack.dailyreport.DailyInventoryReportHandler::handleRequest", lambdaRoleArn, "functions/daily-inventory-report/target/daily-inventory-report-1.0-SNAPSHOT.jar");

            // 6. Step Functions State Machine
            logger.info("\n--- Step 6: Provisioning Step Functions State Machine ---");
            String stateMachineArn = StepFunctionsSetup.setupStateMachine(sfnClient, "OrderFulfillment", sfnRoleArn, "statemachine/order-fulfillment.asl.json");

            // 7. EventBridge Scheduler
            logger.info("\n--- Step 7: Provisioning EventBridge Scheduler ---");
            SchedulerSetup.setupDailySchedule(schedulerClient, "daily-inventory-report", dailyReportArn, lambdaRoleArn);

            // 8. Systems Manager Parameter Store
            logger.info("\n--- Step 8: Storing Configuration in SSM Parameter Store ---");
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/orders-table-name", "Orders");
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/inventory-table-name", "Inventory");
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/order-queue-url", orderQueueUrl);
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/email-queue-url", emailQueueUrl);
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/order-outcome-topic-arn", topicArn);
            ParameterStoreSetup.putParameter(ssmClient, "/cloudtrack/state-machine-arn", stateMachineArn);

            logger.info("\n{}", SEPARATOR);
            logger.info("  CloudTrack Infrastructure Provisioning Complete ");
            logger.info("{}", SEPARATOR);

        } catch (Exception e) {
            logger.error("Provisioning failed: {}", e.getMessage(), e);
        }
    }
}
