package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

public class Main {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("  CloudTrack AWS Infrastructure SDK Provisioner ");
        System.out.println("=================================================");

        try (IamClient iam = IamClient.create();
             DynamoDbClient ddb = DynamoDbClient.create();
             SqsClient sqs = SqsClient.create();
             SnsClient sns = SnsClient.create();
             LambdaClient lambda = LambdaClient.create();
             SfnClient sfn = SfnClient.create();
             SchedulerClient scheduler = SchedulerClient.create();
             SsmClient ssm = SsmClient.create()) {

            // 1. IAM Roles
            System.out.println("\n--- Step 1: Setting up IAM Roles ---");
            String lambdaRoleArn = IamSetup.setupLambdaRole(iam, "CloudTrackLambdaRole");
            String sfnRoleArn = IamSetup.setupStepFunctionsRole(iam, "CloudTrackStepFunctionsRole");

            // 2. DynamoDB Tables
            System.out.println("\n--- Step 2: Provisioning DynamoDB Tables ---");
            DynamoDbSetup.setupOrdersTable(ddb, "Orders");
            DynamoDbSetup.setupInventoryTable(ddb, "Inventory");

            // 3. SQS Queues
            System.out.println("\n--- Step 3: Provisioning SQS Queues ---");
            String orderQueueUrl = SqsSetup.setupQueueWithDlq(sqs, "OrderQueue", "OrderQueueDLQ");
            String emailQueueUrl = SqsSetup.setupQueueWithDlq(sqs, "EmailQueue", "EmailQueueDLQ");
            String emailQueueArn = SqsSetup.getQueueArn(sqs, emailQueueUrl);
            String orderQueueArn = SqsSetup.getQueueArn(sqs, orderQueueUrl);

            // 4. SNS Topic
            System.out.println("\n--- Step 4: Provisioning SNS Topic & Subscription ---");
            String topicArn = SnsSetup.setupTopicAndSubscription(sns, "OrderOutcome", emailQueueArn);

            // 5. Lambda Functions
            System.out.println("\n--- Step 5: Provisioning Lambda Functions ---");
            String createOrderArn = LambdaSetup.createOrUpdateFunction(lambda, "CreateOrder", "com.cloudtrack.createorder.CreateOrderHandler::handleRequest", lambdaRoleArn, "functions/create-order/target/create-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambda, "CreateOrder");

            LambdaSetup.createOrUpdateFunction(lambda, "GetOrder", "com.cloudtrack.getorder.GetOrderHandler::handleRequest", lambdaRoleArn, "functions/get-order/target/get-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambda, "GetOrder");

            LambdaSetup.createOrUpdateFunction(lambda, "ListOrders", "com.cloudtrack.listorders.ListOrdersHandler::handleRequest", lambdaRoleArn, "functions/list-orders/target/list-orders-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambda, "ListOrders");

            LambdaSetup.createOrUpdateFunction(lambda, "GetOrderStatus", "com.cloudtrack.getorderstatus.GetOrderStatusHandler::handleRequest", lambdaRoleArn, "functions/get-order-status/target/get-order-status-1.0-SNAPSHOT.jar");
            LambdaSetup.createFunctionUrl(lambda, "GetOrderStatus");

            String processOrderArn = LambdaSetup.createOrUpdateFunction(lambda, "ProcessOrder", "com.cloudtrack.processorder.ProcessOrderHandler::handleRequest", lambdaRoleArn, "functions/process-order/target/process-order-1.0-SNAPSHOT.jar");
            LambdaSetup.createSqsEventSourceMapping(lambda, "ProcessOrder", orderQueueArn);

            LambdaSetup.createOrUpdateFunction(lambda, "CheckInventory", "com.cloudtrack.checkinventory.CheckInventoryHandler::handleRequest", lambdaRoleArn, "functions/check-inventory/target/check-inventory-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambda, "ReserveStock", "com.cloudtrack.reservestock.ReserveStockHandler::handleRequest", lambdaRoleArn, "functions/reserve-stock/target/reserve-stock-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambda, "RollbackReservations", "com.cloudtrack.rollbackreservations.RollbackReservationsHandler::handleRequest", lambdaRoleArn, "functions/rollback-reservations/target/rollback-reservations-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambda, "PublishOutcome", "com.cloudtrack.publishoutcome.PublishOutcomeHandler::handleRequest", lambdaRoleArn, "functions/publish-outcome/target/publish-outcome-1.0-SNAPSHOT.jar");
            LambdaSetup.createOrUpdateFunction(lambda, "UpdateOrderStatus", "com.cloudtrack.updateorderstatus.UpdateOrderStatusHandler::handleRequest", lambdaRoleArn, "functions/update-order-status/target/update-order-status-1.0-SNAPSHOT.jar");

            String sendEmailArn = LambdaSetup.createOrUpdateFunction(lambda, "SendCustomerEmail", "com.cloudtrack.sendemail.SendCustomerEmailHandler::handleRequest", lambdaRoleArn, "functions/send-customer-email/target/send-customer-email-1.0-SNAPSHOT.jar");
            LambdaSetup.createSqsEventSourceMapping(lambda, "SendCustomerEmail", emailQueueArn);

            String dailyReportArn = LambdaSetup.createOrUpdateFunction(lambda, "DailyInventoryReport", "com.cloudtrack.dailyreport.DailyInventoryReportHandler::handleRequest", lambdaRoleArn, "functions/daily-inventory-report/target/daily-inventory-report-1.0-SNAPSHOT.jar");

            // 6. Step Functions State Machine
            System.out.println("\n--- Step 6: Provisioning Step Functions State Machine ---");
            String stateMachineArn = StepFunctionsSetup.setupStateMachine(sfn, "OrderFulfillment", sfnRoleArn, "statemachine/order-fulfillment.asl.json");

            // 7. EventBridge Scheduler
            System.out.println("\n--- Step 7: Provisioning EventBridge Scheduler ---");
            SchedulerSetup.setupDailySchedule(scheduler, "daily-inventory-report", dailyReportArn, lambdaRoleArn);

            // 8. Systems Manager Parameter Store
            System.out.println("\n--- Step 8: Storing Configuration in SSM Parameter Store ---");
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/orders-table-name", "Orders");
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/inventory-table-name", "Inventory");
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/order-queue-url", orderQueueUrl);
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/email-queue-url", emailQueueUrl);
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/order-outcome-topic-arn", topicArn);
            ParameterStoreSetup.putParameter(ssm, "/cloudtrack/state-machine-arn", stateMachineArn);

            System.out.println("\n=================================================");
            System.out.println("  CloudTrack Infrastructure Provisioning Complete ");
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("Provisioning failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
