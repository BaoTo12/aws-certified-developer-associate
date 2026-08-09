package com.cloudtrack.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderStatusResponse {
    private String orderId;
    private String workflowStatus;
    private String startDate;
    private String stopDate;

    public OrderStatusResponse() {
    }

    public OrderStatusResponse(String orderId, String workflowStatus, String startDate, String stopDate) {
        this.orderId = orderId;
        this.workflowStatus = workflowStatus;
        this.startDate = startDate;
        this.stopDate = stopDate;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getWorkflowStatus() {
        return workflowStatus;
    }

    public void setWorkflowStatus(String workflowStatus) {
        this.workflowStatus = workflowStatus;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getStopDate() {
        return stopDate;
    }

    public void setStopDate(String stopDate) {
        this.stopDate = stopDate;
    }
}
