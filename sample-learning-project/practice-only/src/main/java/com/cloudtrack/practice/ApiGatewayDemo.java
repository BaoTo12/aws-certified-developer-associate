package com.cloudtrack.practice;

import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.*;

public class ApiGatewayDemo {

    public static void main(String[] args) {
        String apiName = "cloudtrack-practice-api-" + System.currentTimeMillis();
        System.out.println("=== Amazon API Gateway Practice Demo ===");

        try (ApiGatewayV2Client apiGatewayClient = ApiGatewayV2Client.create()) {

            // 1. Create API (HTTP API)
            System.out.println("Creating HTTP API: " + apiName);
            CreateApiResponse createApiResponse = apiGatewayClient.createApi(r -> r
                    .name(apiName)
                    .protocolType(ProtocolType.HTTP)
                    .description("Short-lived API Gateway practice instance for DVA-C02"));
            String apiId = createApiResponse.apiId();
            System.out.println("API created with ID: " + apiId);

            // 2. Create Stage ($default) with throttling
            System.out.println("Creating $default stage with throttling settings...");
            apiGatewayClient.createStage(r -> r
                    .apiId(apiId)
                    .stageName("$default")
                    .autoDeploy(true)
                    .defaultRouteSettings(RouteSettings.builder()
                            .throttlingBurstLimit(100)
                            .throttlingRateLimit(50.0)
                            .detailedMetricsEnabled(true)
                            .build()));

            // 3. Create Route (GET /demo)
            System.out.println("Creating route GET /demo...");
            CreateRouteResponse routeResponse = apiGatewayClient.createRoute(r -> r
                    .apiId(apiId)
                    .routeKey("GET /demo")
                    .target("integrations/dummy"));
            System.out.println("Route created: " + routeResponse.routeId());

            // 4. Teardown (DeleteApi)
            System.out.println("Tearing down API Gateway resource (DeleteApi)...");
            apiGatewayClient.deleteApi(r -> r.apiId(apiId));
            System.out.println("API Gateway resource deleted successfully.");

        } catch (Exception e) {
            System.err.println("API Gateway demo error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
