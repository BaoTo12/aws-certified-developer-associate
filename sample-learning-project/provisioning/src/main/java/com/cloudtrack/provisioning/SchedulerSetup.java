package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleResponse;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;

public class SchedulerSetup {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerSetup.class);
    private static final String SCHEDULER_PREFIX = "[EventBridge Scheduler] ";

    private SchedulerSetup() {
        // Utility class private constructor
    }

    public static String setupDailySchedule(SchedulerClient scheduler, String scheduleName, String targetLambdaArn, String roleArn) {
        logger.info("{}Setting up schedule {}...", SCHEDULER_PREFIX, scheduleName);

        try {
            GetScheduleResponse getResp = scheduler.getSchedule(r -> r.name(scheduleName));
            logger.info("{}Schedule {} already exists: {}", SCHEDULER_PREFIX, scheduleName, getResp.arn());
            return getResp.arn();
        } catch (ResourceNotFoundException e) {
            logger.info("{}Creating schedule {}...", SCHEDULER_PREFIX, scheduleName);
        }

        CreateScheduleResponse createResp = scheduler.createSchedule(r -> r
                .name(scheduleName)
                .scheduleExpression("rate(1 day)")
                .flexibleTimeWindow(ftw -> ftw.mode(FlexibleTimeWindowMode.OFF))
                .target(t -> t.arn(targetLambdaArn).roleArn(roleArn)));

        logger.info("{}Schedule created: {}", SCHEDULER_PREFIX, createResp.scheduleArn());
        return createResp.scheduleArn();
    }
}
