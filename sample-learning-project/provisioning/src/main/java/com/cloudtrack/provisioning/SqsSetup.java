package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.Map;

public class SqsSetup {

    private static final Logger logger = LoggerFactory.getLogger(SqsSetup.class);
    private static final String SQS_PREFIX = "[SQS] ";

    private SqsSetup() {
        // Utility class private constructor
    }

    public static String setupQueueWithDlq(SqsClient sqs, String queueName, String dlqName) {
        logger.info("{}Setting up queue {} and DLQ {}...", SQS_PREFIX, queueName, dlqName);

        String dlqUrl;
        try {
            dlqUrl = sqs.getQueueUrl(r -> r.queueName(dlqName)).queueUrl();
            logger.info("{}DLQ {} exists: {}", SQS_PREFIX, dlqName, dlqUrl);
        } catch (QueueDoesNotExistException e) {
            dlqUrl = sqs.createQueue(r -> r.queueName(dlqName)).queueUrl();
            logger.info("{}Created DLQ {}: {}", SQS_PREFIX, dlqName, dlqUrl);
        }

        String dlqArn = sqs.getQueueAttributes(r -> r
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = String.format("{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"3\"}", dlqArn);

        String mainQueueUrl;
        try {
            mainQueueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            logger.info("{}Main queue {} exists: {}", SQS_PREFIX, queueName, mainQueueUrl);
        } catch (QueueDoesNotExistException e) {
            mainQueueUrl = sqs.createQueue(r -> r
                    .queueName(queueName)
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY, redrivePolicy,
                            QueueAttributeName.VISIBILITY_TIMEOUT, "60"
                    ))).queueUrl();
            logger.info("{}Created main queue {}: {}", SQS_PREFIX, queueName, mainQueueUrl);
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
