package com.cloudtrack.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sfn.model.StateMachineType;

import java.nio.file.Files;
import java.nio.file.Path;

public class StepFunctionsSetup {

    private static final Logger logger = LoggerFactory.getLogger(StepFunctionsSetup.class);
    private static final String SFN_PREFIX = "[Step Functions] ";
    private static final String FALLBACK_ASL = "{\"Comment\":\"Fallback\",\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";

    private StepFunctionsSetup() {
        // Utility class private constructor
    }

    public static String setupStateMachine(SfnClient sfn, String stateMachineName, String roleArn, String aslPathStr) {
        logger.info("{}Setting up state machine {}...", SFN_PREFIX, stateMachineName);

        String asl = readAslDefinition(aslPathStr);

        try {
            ListStateMachinesResponse listResp = sfn.listStateMachines();
            for (StateMachineListItem item : listResp.stateMachines()) {
                if (item.name().equals(stateMachineName)) {
                    logger.info("{}State machine {} exists: {}", SFN_PREFIX, stateMachineName, item.stateMachineArn());
                    return item.stateMachineArn();
                }
            }
        } catch (Exception e) {
            logger.warn("{}Could not list existing state machines: {}", SFN_PREFIX, e.getMessage());
        }

        CreateStateMachineResponse createResp = sfn.createStateMachine(r -> r
                .name(stateMachineName)
                .definition(asl)
                .roleArn(roleArn)
                .type(StateMachineType.STANDARD)
                .tracingConfiguration(tc -> tc.enabled(true)));

        logger.info("{}Created state machine {}: {}", SFN_PREFIX, stateMachineName, createResp.stateMachineArn());
        return createResp.stateMachineArn();
    }

    private static String readAslDefinition(String aslPathStr) {
        try {
            Path aslPath = Path.of(aslPathStr);
            if (Files.exists(aslPath)) {
                return Files.readString(aslPath);
            }
        } catch (Exception e) {
            logger.warn("{}Error reading ASL file at {}: {}", SFN_PREFIX, aslPathStr, e.getMessage());
        }
        return FALLBACK_ASL;
    }
}
