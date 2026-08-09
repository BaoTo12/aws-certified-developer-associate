package com.cloudtrack.common.dto;

import com.cloudtrack.common.model.Order;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ListOrdersResponse {
    private List<Order> orders;
    private String nextToken;

    public ListOrdersResponse() {
    }

    public ListOrdersResponse(List<Order> orders, String nextToken) {
        this.orders = orders;
        this.nextToken = nextToken;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public void setOrders(List<Order> orders) {
        this.orders = orders;
    }

    public String getNextToken() {
        return nextToken;
    }

    public void setNextToken(String nextToken) {
        this.nextToken = nextToken;
    }
}
