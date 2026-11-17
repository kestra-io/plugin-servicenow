package io.kestra.plugin.servicenow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.serializers.JacksonMapper;

final class ServiceNowValues {
    static final int MAX_FIELDS = 256;
    static final int MAX_TEXT = 65536;
    static final int MAX_ROW_BYTES = 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");
    private static final Pattern SYS_ID = Pattern.compile("[a-fA-F0-9]{32}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ServiceNowValues() {
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new IllegalArgumentException("ServiceNow %s must be non-blank and at most %d characters.".formatted(name, MAX_TEXT));
        }
        return value.trim();
    }

    static String name(String value, String property) {
        var result = required(value, property);
        if (!NAME.matcher(result).matches()) {
            throw new IllegalArgumentException("ServiceNow %s must be an API field/table name using letters, digits and underscores, at most 80 characters.".formatted(property));
        }
        return result;
    }

    static String sysId(String value, String property) {
        var result = required(value, property);
        if (!SYS_ID.matcher(result).matches()) {
            throw new IllegalArgumentException("ServiceNow %s must be a 32-character hexadecimal sys_id.".formatted(property));
        }
        return result.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isSysId(String value) {
        return value != null && SYS_ID.matcher(value.trim()).matches();
    }

    static JsonNode record(JsonNode response) {
        var result = response.path("result");
        if (result.isArray() && result.size() == 1) {
            result = result.get(0);
        }
        if (!result.isObject()) {
            throw new IllegalStateException("ServiceNow must return exactly one result record. Check the API and resource identifier.");
        }
        return result;
    }

    static JsonNode value(JsonNode field) {
        return field != null && field.isObject() && field.has("value") ? field.get("value") : field;
    }

    static String text(JsonNode field) {
        var value = value(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (!value.isValueNode()) {
            throw new IllegalStateException("ServiceNow returned a structured value where a scalar was required. Check the API response.");
        }
        return value.asText();
    }

    static String field(JsonNode record, String name) {
        return text(record.get(name));
    }

    static String recordId(JsonNode record) {
        return responseId(field(record, "sys_id"), "sys_id");
    }

    static String responseId(String value, String property) {
        try {
            return sysId(value, "response " + property);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ServiceNow returned an invalid %s. Stop and check the API result before retrying.".formatted(property), e);
        }
    }

    static Map<String, Object> map(JsonNode object) {
        if (!object.isObject()) {
            throw new IllegalStateException("ServiceNow returned a non-object record. Check the API response.");
        }
        return JacksonMapper.ofJson().convertValue(object, new TypeReference<>() {
        });
    }

    static Map<String, String> stringValues(Map<String, Object> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("ServiceNow values must contain between 1 and %d fields.".formatted(MAX_FIELDS));
        }
        var result = new LinkedHashMap<String, String>();
        var bytes = 0L;
        for (var entry : values.entrySet()) {
            var key = name(entry.getKey(), "field name");
            var raw = entry.getValue();
            String value;
            if (raw instanceof String string) {
                value = string;
            } else if (raw instanceof Boolean booleanValue) {
                value = booleanValue.toString();
            } else if (raw instanceof Number number) {
                try {
                    value = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("ServiceNow field %s must contain a finite scalar value.".formatted(key), e);
                }
            } else {
                throw new IllegalArgumentException("ServiceNow field %s must be a string, finite number or boolean; null and nested values are not supported.".formatted(key));
            }
            if (value.length() > MAX_TEXT) {
                throw new IllegalArgumentException("ServiceNow field %s exceeds %d characters.".formatted(key, MAX_TEXT));
            }
            bytes += key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > MAX_ROW_BYTES) {
                throw new IllegalArgumentException("ServiceNow row exceeds 1 MiB. Split or narrow its fields.");
            }
            result.put(key, value);
        }
        return result;
    }

    static void verify(JsonNode record, Map<String, String> expected) {
        ignoredFields(record);
        for (var entry : expected.entrySet()) {
            var actual = field(record, entry.getKey());
            if (!entry.getValue().equals(actual)) {
                throw new IllegalStateException("ServiceNow did not persist field %s as requested. Check its ACL, transform/reconciliation rules and value type.".formatted(entry.getKey()));
            }
        }
    }

    static void ignoredFields(JsonNode record) {
        var ignored = record.path("__meta").path("ignoredFields");
        if (ignored.isArray() && !ignored.isEmpty()) {
            throw new IllegalStateException("ServiceNow ignored request fields. Check field names and write ACLs before proceeding.");
        }
    }

    static String date(String value, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Instant instant;
            try {
                instant = OffsetDateTime.parse(value).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                instant = LocalDateTime.parse(value, DATE).toInstant(ZoneOffset.UTC);
            }
            return DATE.format(instant.atOffset(ZoneOffset.UTC));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("ServiceNow %s must be an ISO timestamp with offset or yyyy-MM-dd HH:mm:ss in UTC.".formatted(property), e);
        }
    }

    static Duration duration(Duration value, Duration fallback, String property, Duration maximum) {
        var result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero() || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("ServiceNow %s must be positive and no greater than %s.".formatted(property, maximum));
        }
        return result;
    }
}
