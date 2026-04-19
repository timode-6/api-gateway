package com.innowise.api_gateway.deadletter;

import lombok.*;

@Builder
@Value
public class RollbackFailedEvent {
    Long   userId;
    String reason;
    String requiredAction;    
    String timestamp;
    int    rollbackAttempts;
}