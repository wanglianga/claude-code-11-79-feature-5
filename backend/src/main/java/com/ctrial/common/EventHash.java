package com.ctrial.common;

import java.time.Instant;

/**
 * Canonical content hash for a source event. Used by both the demo seeder and
 * the live ingest path so a genuine re-send of a seeded record resolves to the
 * same row instead of looking like a hash conflict.
 */
public final class EventHash {

    private EventHash() {
    }

    public static String of(String stableId, int revision, String domain, Instant occurredAt,
                            String protocolVersion, String fieldGroups, String payloadJson, boolean voided) {
        return Text.sha256(String.join("|",
                stableId,
                String.valueOf(revision),
                domain,
                occurredAt == null ? "" : occurredAt.toString(),
                protocolVersion == null ? "" : protocolVersion,
                fieldGroups == null ? "" : fieldGroups,
                payloadJson == null ? "" : payloadJson,
                String.valueOf(voided)));
    }
}
