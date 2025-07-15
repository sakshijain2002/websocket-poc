//package com.websocket.websocketpoc.scheduler;
//
//import com.websocket.websocketpoc.service.S3BatchWriterService;
//import jakarta.annotation.PreDestroy;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//@Component
//@RequiredArgsConstructor
//@Slf4j
//public class ShutDownHandler {
//
//    private final S3BatchWriterService writer;
//
//    @PreDestroy
//    public void onShutdown() {
//        log.info("Shutdown triggered. Persisting Redis stream to S3...");
//        writer.writeGroupedData(); // This is async. If needed, use latch or blocking
//    }
//}
