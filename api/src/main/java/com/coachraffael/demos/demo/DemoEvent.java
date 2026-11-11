package com.coachraffael.demos.demo;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;

public record DemoEvent(long id, String action, String actor, String channel, boolean ok, @JsonRawValue String detail,
                        OffsetDateTime at) {
}
