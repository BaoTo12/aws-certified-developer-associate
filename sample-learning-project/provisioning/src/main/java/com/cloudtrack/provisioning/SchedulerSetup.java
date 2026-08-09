package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;

public class SchedulerSetup {

    public static String setupDailySchedule(SchedulerClient scheduler, String scheduleName, String targetLambdaArn, String roleArn) {
        System.out.println("[EventBridge Scheduler] Setting up schedule " + scheduleName + "...");

        try {
            GetScheduleResponse getResp = scheduler.getSchedule(r -> r.name(scheduleName));
            System.out.println("[EventBridge Scheduler] Schedule " + scheduleName + " already exists: " + getResp.arn());
            return getResp.arn();
        } catch (ResourceNotFoundException e) {
            System.out.println("[EventBridge Scheduler] Creating schedule " + scheduleName + "...");
        }

        CreateScheduleResponse createResp = scheduler.createSchedule(r -> r
                .name(scheduleName)
                .scheduleExpression("rate(1 day)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder()
                        .arn(targetLambdaArn)
                        .roleArn(roleArn)
                        .build()));

        System.out.println("[EventBridge Scheduler] Schedule created: " + createResp.scheduleArn());
        return createResp.scheduleArn();
    }
}
