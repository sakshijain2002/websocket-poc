package com.websocket.websocketpoc.scheduler;

import com.websocket.websocketpoc.config.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocationScheduler {

    private final LocationWebSocketHandler handler;

    @Scheduled(fixedRate = 120000) // Every 2 minutes
    public void SaveToS3() {
        handler.persistsBufferedDataToS3();
    }
}