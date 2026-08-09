package com.cloudtrack.common.dto;

import com.cloudtrack.common.model.OrderItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateOrderRequest {
    private String customerId;
    private String customerEmail;
    private List<OrderItem> items;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String customerId, String customerEmail, List<OrderItem> items) {
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.items = items;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }
}
