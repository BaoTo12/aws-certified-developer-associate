package com.cloudtrack.provisioning;

import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.nio.file.Files;
import java.nio.file.Path;

public class StepFunctionsSetup {

    public static String setupStateMachine(SfnClient sfn, String stateMachineName, String roleArn, String aslPathStr) {
        System.out.println("[Step Functions] Setting up state machine " + stateMachineName + "...");

        String asl;
        try {
            Path aslPath = Path.of(aslPathStr);
            if (Files.exists(aslPath)) {
                asl = Files.readString(aslPath);
            } else {
                asl = "{\"Comment\":\"Fallback\",\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
            }
        } catch (Exception e) {
            asl = "{\"Comment\":\"Fallback\",\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
        }

        try {
            ListStateMachinesResponse listResp = sfn.listStateMachines();
            for (StateMachineListItem item : listResp.stateMachines()) {
                if (item.name().equals(stateMachineName)) {
                    System.out.println("[Step Functions] State machine " + stateMachineName + " exists: " + item.stateMachineArn());
                    return item.stateMachineArn();
                }
            }
        } catch (Exception ignored) {}

        CreateStateMachineResponse createResp = sfn.createStateMachine(r -> r
                .name(stateMachineName)
                .definition(asl)
                .roleArn(roleArn)
                .type(StateMachineType.STANDARD)
                .tracingConfiguration(TracingConfiguration.builder().enabled(true).build()));

        System.out.println("[Step Functions] Created state machine " + stateMachineName + ": " + createResp.stateMachineArn());
        return createResp.stateMachineArn();
    }
}
