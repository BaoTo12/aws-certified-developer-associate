package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

public class SqsSetup {

    public static String setupQueueWithDlq(SqsClient sqs, String queueName, String dlqName) {
        System.out.println("[SQS] Setting up queue " + queueName + " and DLQ " + dlqName + "...");

        String dlqUrl;
        try {
            dlqUrl = sqs.getQueueUrl(r -> r.queueName(dlqName)).queueUrl();
            System.out.println("[SQS] DLQ " + dlqName + " exists: " + dlqUrl);
        } catch (QueueDoesNotExistException e) {
            dlqUrl = sqs.createQueue(r -> r.queueName(dlqName)).queueUrl();
            System.out.println("[SQS] Created DLQ " + dlqName + ": " + dlqUrl);
        }

        String dlqArn = sqs.getQueueAttributes(r -> r
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = String.format("{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"3\"}", dlqArn);

        String mainQueueUrl;
        try {
            mainQueueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            System.out.println("[SQS] Main queue " + queueName + " exists: " + mainQueueUrl);
        } catch (QueueDoesNotExistException e) {
            mainQueueUrl = sqs.createQueue(r -> r
                    .queueName(queueName)
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY, redrivePolicy,
                            QueueAttributeName.VISIBILITY_TIMEOUT, "60"
                    ))).queueUrl();
            System.out.println("[SQS] Created main queue " + queueName + ": " + mainQueueUrl);
        }

        return mainQueueUrl;
    }

    public static String getQueueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(r -> r
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }
}
