package com.websocket.websocketpoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.websocket.websocketpoc.model.LocationModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3BatchWriterService {

    private final S3AsyncClient s3Client;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${aws.s3.bucket}")
    private String bucketName;

    public void writeGroupedData(List<LocationModel> records, Instant writeTime) {
        if (records == null || records.isEmpty()) return;

        Map<String, List<LocationModel>> groupedByUser = records.stream()
                .collect(Collectors.groupingBy(record ->
                        String.join("-", record.getPatrollingId(), record.getRouteId(), record.getUserId())
                ));

        groupedByUser.forEach((ignoredKey, userRecords) -> {
            String key = buildS3Key(userRecords.get(0), writeTime);
            writeToS3(key, userRecords);
        });
    }

    private String buildS3Key(LocationModel record, Instant writeTime) {
        var time = writeTime.atZone(ZoneId.of("UTC"));
        String timestamp = time.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")); // SSS for millis

        return String.format("DriverLocation/%s/%s_%s_%s.json",
                record.getPatrollingId(),
                record.getRouteId(),
                record.getUserId(),
                timestamp
        );
    }

    private void writeToS3(String key, List<LocationModel> records) {
        try {
            String jsonArray = objectMapper.writeValueAsString(records);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .build();

            s3Client.putObject(request, AsyncRequestBody.fromString(jsonArray))
                    .thenAccept(response -> log.info(" Wrote {} records to S3 key: {}", records.size(), key))
                    .exceptionally(e -> {
                        log.error(" Failed writing to S3 key: {}", key, e);
                        return null;
                    });

        } catch (Exception e) {
            log.error(" Error serializing S3 data for key: {}", key, e);
        }
    }
}
