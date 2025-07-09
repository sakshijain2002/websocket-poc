package com.websocket.websocketpoc.repository;

import com.websocket.websocketpoc.model.LocationModel;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface LocationRepository extends ReactiveCrudRepository<LocationModel, String> {
    Mono<LocationModel> findByUserId(String userId);
    Optional<LocationModel> findByUserIdAndPatrollingId(String userId, String patrollingId);
}
