package com.websocket.websocketpoc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.time.Instant;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationModel {


    private String userId;
    private String patrollingId;
    private String routeId;
    private double latitude;
    private double longitude;
    private LocalDateTime timestamp;
}
