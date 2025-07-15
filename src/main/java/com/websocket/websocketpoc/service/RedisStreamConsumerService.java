package com.websocket.websocketpoc.service;

import com.websocket.websocketpoc.model.LocationModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamConsumerService {

    private final RedisTemplate<String, String> redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final S3BatchWriterService writerService;

    private final Map<String, List<LocationModel>> buffer = new ConcurrentHashMap<>();

    @Value("${location.redis.stream.key}")
    private String streamKey;

    @Value("${redis.stream.consumer-group}")
    private String consumerGroup;

    @Value("${redis.stream.consumer-name}")
    private String consumerName;

    @Value("${redis.stream.buffer-size}")
    private int maxBufferSize;

    @Value("${redis.stream.write-interval-seconds}")
    private int writeIntervalSeconds;

    @PostConstruct
    public void start() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
        } catch (Exception e) {
            log.warn("Redis group already exists: {}", e.getMessage());
        }

        container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                this::handleMessage
        );
        container.start();
        log.info(" Redis stream consumer started");

        Flux.interval(Duration.ofSeconds(writeIntervalSeconds))
                .doOnNext(tick -> writeBufferedRecordsToS3())
                .subscribe();
    }

    private void handleMessage(MapRecord<String, String, String> message) {
        try {
            Map<String, String> valueMap = message.getValue();

            LocationModel data = LocationModel.builder()
                    .patrollingId(valueMap.get("patrollingId"))
                    .routeId(valueMap.get("routeId"))
                    .userId(valueMap.get("userId"))
                    .latitude(Double.parseDouble(valueMap.get("latitude")))
                    .longitude(Double.parseDouble(valueMap.get("longitude")))
                    .timestamp(LocalDateTime.parse(valueMap.get("timestamp")))
                    .build();

            String key = String.join("-", data.getPatrollingId(), data.getRouteId(), data.getUserId());
            buffer.computeIfAbsent(key, k -> new ArrayList<>()).add(data);

            if (buffer.values().stream().mapToInt(List::size).sum() > maxBufferSize) {
                writeBufferedRecordsToS3();
            }

        } catch (Exception e) {
            log.error(" Error handling Redis stream message", e);
        }
    }

    private synchronized void writeBufferedRecordsToS3() {
        if (buffer.isEmpty()) return;

        Map<String, List<LocationModel>> snapshot = new HashMap<>(buffer);
        buffer.clear();

        Instant currentWriteTime = Instant.now();

        snapshot.forEach((key, records) -> {
            if (!records.isEmpty()) {
                writerService.writeGroupedData(records, currentWriteTime);
            }
        });

        log.info(" Wrote {} user groups to S3", snapshot.size());
    }
}
