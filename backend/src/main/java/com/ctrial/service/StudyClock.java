package com.ctrial.service;

import com.ctrial.model.Study;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Converts between study days and instants. Day 1 starts at the study epoch.
 * Every rule (consent window, protocol effective date, observation window)
 * is expressed in these terms so that "day 11" means one specific instant.
 */
@Service
public class StudyClock {

    public static final ZoneOffset ZONE = ZoneOffset.UTC;

    public Instant dayStart(Study study, int day) {
        return study.getEpoch().plus(day - 1L, ChronoUnit.DAYS);
    }

    public Instant atDay(Study study, int day, int hour) {
        return dayStart(study, day).plus(hour, ChronoUnit.HOURS);
    }

    public Instant endOfDay(Study study, int day) {
        return dayStart(study, day + 1);
    }

    /**
     * Analysis plane instant: 23:00 UTC on the given study day. Using a single
     * fixed hour of the day keeps the boundary unambiguous: a withdrawal signed
     * on day 12 at 09:00 is *after* the day-11 analysis instant and *before* the
     * day-12 one, so a day-11 delivery can legitimately contain a day-11 record
     * that a later withdrawal makes unavailable.
     */
    public Instant analysisInstant(Study study, int day) {
        return dayStart(study, day).plus(23, ChronoUnit.HOURS);
    }

    public int dayOf(Study study, Instant instant) {
        long seconds = Duration.between(study.getEpoch(), instant).getSeconds();
        return (int) Math.floorDiv(seconds, 86400L) + 1;
    }

    public LocalDate dateOf(Study study, Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }
}
