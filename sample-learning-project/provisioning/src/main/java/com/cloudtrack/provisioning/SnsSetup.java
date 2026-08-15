package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

public class SnsSetup {

    private static final Logger logger = LoggerFactory.getLogger(SnsSetup.class);
    private static final String SNS_PREFIX = "[SNS] ";

    private SnsSetup() {
        // Utility class private constructor
    }

    public static String setupTopicAndSubscription(SnsClient sns, String topicName, String emailQueueArn) {
        logger.info("{}Setting up topic {}...", SNS_PREFIX, topicName);

        CreateTopicResponse topicResp = sns.createTopic(r -> r.name(topicName));
        String topicArn = topicResp.topicArn();
        logger.info("{}Topic ARN: {}", SNS_PREFIX, topicArn);

        if (emailQueueArn != null && !emailQueueArn.isBlank()) {
            logger.info("{}Subscribing SQS EmailQueue ({}) with filter policy...", SNS_PREFIX, emailQueueArn);

            SubscribeResponse subResp = sns.subscribe(r -> r
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(emailQueueArn));

            String subArn = subResp.subscriptionArn();
            logger.info("{}Subscription ARN: {}", SNS_PREFIX, subArn);

            if (subArn != null && !subArn.equals("pending confirmation")) {
                sns.setSubscriptionAttributes(r -> r
                        .subscriptionArn(subArn)
                        .attributeName("FilterPolicy")
                        .attributeValue("{\"status\":[\"CONFIRMED\",\"FAILED\"]}"));
                logger.info("{}FilterPolicy applied to subscription: {{\\\"status\\\":[\\\"CONFIRMED\\\",\\\"FAILED\\\"]}}", SNS_PREFIX);
            }
        }

        return topicArn;
    }
}
