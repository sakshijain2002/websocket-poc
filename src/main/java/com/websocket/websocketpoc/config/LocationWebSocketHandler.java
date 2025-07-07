package com.websocket.websocketpoc.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.websocket.websocketpoc.model.message.LocationMessage;
import com.websocket.websocketpoc.model.LocationModel;
import com.websocket.websocketpoc.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocationWebSocketHandler {

    private final List<FluxSink<String>> frontendSinks = new CopyOnWriteArrayList<>();
    private final LocationRepository locationRepository;
    private final S3Client s3Client;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${aws.s3.bucket}")
    private String bucketName;

    // WebSocket: Receives data from mobile app
    public WebSocketHandler appHandler() {
        return session -> session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> {
                    try {
                        LocationMessage msg = objectMapper.readValue(payload, LocationMessage.class);

                        LocationModel entity = LocationModel.builder()
                                .userId(msg.getUserId())
                                .latitude(msg.getLatitude())
                                .longitude(msg.getLongitude())
                                .timestamp(LocalDateTime.now())
                                .build();

                        return locationRepository.save(entity)
                                .doOnSuccess(saved -> {
                                    uploadToS3(saved);
                                    try {
                                        String json = objectMapper.writeValueAsString(List.of(saved));
                                        frontendSinks.forEach(sink -> sink.next(json));
                                    } catch (JsonProcessingException ex) {
                                        log.error("Error serializing message for frontend", ex);
                                    }
                                })
                                .then();
                    } catch (Exception e) {
                        log.error("Failed to process incoming message", e);
                        return Mono.empty();
                    }
                })
                .then();
    }

    // WebSocket: Sends data to frontend
    public WebSocketHandler frontendHandler() {
        return session -> {
            Flux<String> output = Flux.<String>create(sink -> {
                frontendSinks.add(sink);

                locationRepository.findAll()
                        .collectList()
                        .flatMap(list -> {
                            try {
                                String json = objectMapper.writeValueAsString(list);
                                return Mono.just(json);
                            } catch (JsonProcessingException e) {
                                log.error("Error serializing initial data for frontend", e);
                                return Mono.empty();
                            }
                        })
                        .subscribe(
                                sink::next,
                                error -> log.error("Error sending data to frontend", error)
                        );

                sink.onDispose(() -> frontendSinks.remove(sink));
            });

            return session.send(output.map(session::textMessage));
        };
    }

    // Upload single location entry to S3
    private void uploadToS3(LocationModel model) {
        try {
            String formattedTimestamp = model.getTimestamp().toString().replace(":", "-");
            String key = String.format("location-logs/%s/%s.json", model.getUserId(), formattedTimestamp);
            String content = objectMapper.writeValueAsString(model);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .build();

            s3Client.putObject(request, RequestBody.fromString(content));
            log.info("Location saved to S3: {}", key);
        } catch (Exception e) {
            log.error("Failed to upload location to S3", e);
        }
    }
}
