package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

public class SnsSetup {

    public static String setupTopicAndSubscription(SnsClient sns, String topicName, String emailQueueArn) {
        System.out.println("[SNS] Setting up topic " + topicName + "...");

        CreateTopicResponse topicResp = sns.createTopic(r -> r.name(topicName));
        String topicArn = topicResp.topicArn();
        System.out.println("[SNS] Topic ARN: " + topicArn);

        if (emailQueueArn != null && !emailQueueArn.isBlank()) {
            System.out.println("[SNS] Subscribing SQS EmailQueue (" + emailQueueArn + ") with filter policy...");

            SubscribeResponse subResp = sns.subscribe(r -> r
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(emailQueueArn));

            String subArn = subResp.subscriptionArn();
            System.out.println("[SNS] Subscription ARN: " + subArn);

            if (subArn != null && !subArn.equals("pending confirmation")) {
                sns.setSubscriptionAttributes(r -> r
                        .subscriptionArn(subArn)
                        .attributeName("FilterPolicy")
                        .attributeValue("{\"status\":[\"CONFIRMED\",\"FAILED\"]}"));
                System.out.println("[SNS] FilterPolicy applied to subscription: {\"status\":[\"CONFIRMED\",\"FAILED\"]}");
            }
        }

        return topicArn;
    }
}
