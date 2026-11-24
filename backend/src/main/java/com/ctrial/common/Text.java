package com.ctrial.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Small shared helpers: comma-separated set storage, hashing, canonicalisation. */
public final class Text {

    private Text() {
    }

    public static String csv(Collection<? extends Enum<?>> values) {
        if (values == null) {
            return "";
        }
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public static String csvStrings(Collection<String> values) {
        if (values == null) {
            return "";
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .map(String::toUpperCase).distinct().sorted().collect(Collectors.joining(","));
    }

    public static <E extends Enum<E>> Set<E> enumSet(String csv, Class<E> type) {
        Set<E> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Enum.valueOf(type, t.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unknown token is skipped; the caller treats absence as "not granted"
            }
        }
        return out;
    }

    public static Set<String> stringSet(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t.toUpperCase());
            }
        }
        return out;
    }

    public static boolean intersects(Collection<String> a, Collection<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> sorted(Collection<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(String::compareTo);
        return out;
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String joinSorted(Collection<String> in) {
        return in.stream().sorted().collect(Collectors.joining("|"));
    }

    public static String label(String code) {
        if (code == null) {
            return "";
        }
        return code.replace('_', ' ').toLowerCase();
    }
}
