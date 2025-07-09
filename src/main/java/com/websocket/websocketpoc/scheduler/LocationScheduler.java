package com.websocket.websocketpoc.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.websocketpoc.config.LocationWebSocketHandler;
import com.websocket.websocketpoc.model.LocationModel;
import com.websocket.websocketpoc.service.LocationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LocationScheduler {

    private final LocationWebSocketHandler handler;
    private final LocationRedisService redisService;
    private final ObjectMapper objectMapper;
    private final S3AsyncClient s3AsyncClient;

    @Value("${location.redis.stream.key:location-stream}")
    private String redisStreamKey;

    @Value("${aws.s3.bucket}")
    private String bucketName;

//
//    @Scheduled(fixedRate = 120000) // Every 2 minutes
//    public void SaveToS3() {
//        handler.persistsBufferedDataToS3();
//    }


//    @Scheduled(fixedRateString = "${location.s3.save.interval:120000}")
//    public void saveToS3FromRedisStream() {
//        redisService.readAllFromStream()
//                .collectList()
//                .flatMap(records -> {
//                    if (records.isEmpty()) return Mono.empty();
//
//                    Map<String, List<LocationModel>> grouped = new HashMap<>();
//
//                    for (var record : records) {
//                        try {
//                            Map<String, String> map = record.getValue();
//                            LocationModel location = LocationModel.builder()
//                                    .userId(map.get("userId"))
//                                    .latitude(Double.parseDouble(map.get("latitude")))
//                                    .longitude(Double.parseDouble(map.get("longitude")))
//                                    .timestamp(LocalDateTime.parse(map.get("timestamp")))
//                                    .patrollingId(map.get("patrollingId"))
//                                    .build();
//
//                            String groupKey = location.getPatrollingId() + "/" + location.getUserId();
//                            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(location);
//
//                        } catch (Exception e) {
//                            log.error("Error parsing Redis record", e);
//                        }
//                    }
//
//                    List<Mono<PutObjectResponse>> uploads = new ArrayList<>();
//
//                    for (Map.Entry<String, List<LocationModel>> entry : grouped.entrySet()) {
//                        String s3Key = "location-logs/" + entry.getKey() + ".json"; // ✅ location-logs/patrollingId/userId.json
//                        try {
//                            String json = objectMapper.writeValueAsString(entry.getValue());
//                            uploads.add(uploadToS3(s3Key, json));
//                        } catch (JsonProcessingException e) {
//                            log.error("Error serializing locations for S3", e);
//                        }
//                    }
//
//                    return Mono.when(uploads)
//                            .then(redisService.deleteStream())
//                            .doOnSuccess(ok -> log.info("✅ Flushed {} grouped files to S3 and cleared Redis stream", uploads.size()));
//                })
//                .subscribe();
//    }
    @Scheduled(fixedRateString = "${location.s3.save.interval:120000}")
    public void saveToS3FromRedisStream() {
        redisService.readAllFromStream()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) return Mono.empty();

                    Map<String, List<LocationModel>> grouped = new HashMap<>();

                    for (var record : records) {
                        try {
                            Map<String, String> map = record.getValue();
                            LocationModel location = LocationModel.builder()
                                    .userId(map.get("userId"))
                                    .latitude(Double.parseDouble(map.get("latitude")))
                                    .longitude(Double.parseDouble(map.get("longitude")))
                                    .timestamp(LocalDateTime.parse(map.get("timestamp")))
                                    .patrollingId(map.get("patrollingId"))
                                    .build();

                            String groupKey = location.getPatrollingId() + "/" + location.getUserId();
                            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(location);

                        } catch (Exception e) {
                            log.error("Error parsing Redis record", e);
                        }
                    }

                    long timestamp = System.currentTimeMillis(); // 🕒 Unique per scheduler run
                    List<Mono<PutObjectResponse>> uploads = new ArrayList<>();

                    for (Map.Entry<String, List<LocationModel>> entry : grouped.entrySet()) {
                        // 👇 Append timestamp to filename
                        String s3Key = "location-logs/" + entry.getKey() + "-" + timestamp + ".json";

                        try {
                            String json = objectMapper.writeValueAsString(entry.getValue());
                            uploads.add(uploadToS3(s3Key, json));
                        } catch (JsonProcessingException e) {
                            log.error("Error serializing locations for S3", e);
                        }
                    }

                    return Mono.when(uploads)
                            .then(redisService.deleteStream())
                            .doOnSuccess(ok -> log.info("✅ Save {} files to S3 with timestamped names", uploads.size()));
                })
                .subscribe();
    }


    private Mono<PutObjectResponse> uploadToS3(String keyPath, String json) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .contentType("application/json")
                .build();

        return Mono.fromFuture(s3AsyncClient.putObject(putRequest, AsyncRequestBody.fromString(json)));
    }
}