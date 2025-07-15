package com.websocket.websocketpoc.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationMessage {
    private String userId;
    private String patrollingId;
    private String routeId;
    private double latitude;
    private double longitude;
}
