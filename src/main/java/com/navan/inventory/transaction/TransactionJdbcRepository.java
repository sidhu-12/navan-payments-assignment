package com.navan.inventory.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.inventory.common.FeeLineDto;
import com.navan.inventory.common.Money;
import com.navan.inventory.common.TaxLineDto;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.TransactionType;
import com.navan.inventory.common.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class TransactionJdbcRepository {

    private static final String TRANSACTION_COLUMNS = "id, external_id, type, currency, occurred_at, total, metadata_json, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    TransactionJdbcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void insert(String id, TransactionRequest request, String type, String currency, String externalId) {
        jdbcTemplate.update(
                "INSERT INTO transactions (id, external_id, type, currency, occurred_at, total, metadata_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, externalId, type, currency, request.occurredAt(), Money.of(request.total()), writeJson(request.metadata())
        );
        insertTaxLines(id, request.taxLines());
        insertFeeLines(id, request.feeLines());
        insertTypeSpecificRows(id, type, request.metadata());
    }

    long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        return count == null ? 0 : count;
    }

    List<TransactionResponse> findPage(int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + TRANSACTION_COLUMNS + " FROM transactions ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                this::mapRow, limit, offset
        );
    }

    Optional<TransactionResponse> findById(String id) {
        List<TransactionResponse> rows = jdbcTemplate.query(
                "SELECT " + TRANSACTION_COLUMNS + " FROM transactions WHERE id = ?", this::mapRow, id
        );
        return rows.stream().findFirst();
    }

    String findIdByExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT id FROM transactions WHERE external_id = ?", String.class, externalId);
    }

    boolean lockById(String id) {
        return !jdbcTemplate.query("SELECT id FROM transactions WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("id"), id).isEmpty();
    }

    List<TransactionSnapshot> lockSnapshots(List<String> ids) {
        List<String> lockOrder = ids.stream().distinct().sorted().toList();
        for (String id : lockOrder) {
            lockById(id);
        }
        List<TransactionSnapshot> snapshots = new ArrayList<>();
        for (String id : ids) {
            Optional<TransactionSnapshot> snapshot = findSnapshot(id);
            snapshot.ifPresent(snapshots::add);
        }
        return snapshots;
    }

    void update(String id, TransactionRequest request, String type, String currency) {
        jdbcTemplate.update(
                "UPDATE transactions SET external_id = ?, type = ?, currency = ?, occurred_at = ?, total = ?, metadata_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                request.externalId().trim(), type, currency, request.occurredAt(), Money.of(request.total()), writeJson(request.metadata()), id
        );
        clearTypeSpecificRows(id);
        insertTaxLines(id, request.taxLines());
        insertFeeLines(id, request.feeLines());
        insertTypeSpecificRows(id, type, request.metadata());
    }

    void delete(String id) {
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", id);
    }

    boolean isInvoiced(String transactionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice_lines WHERE transaction_id = ?", Integer.class, transactionId
        );
        return count != null && count > 0;
    }

    Optional<TransactionSnapshot> findSnapshot(String id) {
        return findById(id).map(row -> new TransactionSnapshot(
                row.id(), TransactionType.fromWire(row.type()), row.currency(), row.total(),
                row.taxLines(), row.feeLines(), row.metadata()
        ));
    }

    private TransactionResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        try {
            JsonNode metadata = objectMapper.readTree(rs.getString("metadata_json"));
            return new TransactionResponse(
                    id, rs.getString("external_id"), rs.getString("type"), rs.getString("currency"),
                    rs.getObject("occurred_at", OffsetDateTime.class), rs.getBigDecimal("total"),
                    readTaxLines(id), readFeeLines(id), metadata,
                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)
            );
        } catch (JsonProcessingException e) {
            throw new SQLException("Unable to decode metadata for transaction " + id, e);
        }
    }

    private List<TaxLineDto> readTaxLines(String transactionId) {
        return jdbcTemplate.query(
                "SELECT name, rate, amount FROM tax_lines WHERE transaction_id = ? ORDER BY id",
                (rs, rowNum) -> new TaxLineDto(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")),
                transactionId
        );
    }

    private List<FeeLineDto> readFeeLines(String transactionId) {
        return jdbcTemplate.query(
                "SELECT name, amount FROM fee_lines WHERE transaction_id = ? ORDER BY id",
                (rs, rowNum) -> new FeeLineDto(rs.getString("name"), rs.getBigDecimal("amount")),
                transactionId
        );
    }

    private void insertTaxLines(String transactionId, List<TaxLineDto> taxLines) {
        if (taxLines == null) return;
        for (TaxLineDto line : taxLines) {
            if (line != null) {
                jdbcTemplate.update(
                        "INSERT INTO tax_lines (transaction_id, name, rate, amount) VALUES (?, ?, ?, ?)",
                        transactionId, line.name(), line.rate(), line.amount()
                );
            }
        }
    }

    private void insertFeeLines(String transactionId, List<FeeLineDto> feeLines) {
        if (feeLines == null) return;
        for (FeeLineDto line : feeLines) {
            if (line != null) {
                jdbcTemplate.update(
                        "INSERT INTO fee_lines (transaction_id, name, amount) VALUES (?, ?, ?)",
                        transactionId, line.name(), line.amount()
                );
            }
        }
    }

    private void insertTypeSpecificRows(String transactionId, String type, JsonNode metadata) {
        switch (type) {
            case "flight" -> {
                jdbcTemplate.update(
                        "INSERT INTO flight_details (transaction_id, origin, destination, fare) VALUES (?, ?, ?, ?)",
                        transactionId, metadata.get("origin").asText(), metadata.get("destination").asText(),
                        Money.of(new BigDecimal(metadata.get("fare").asText()))
                );
                if (metadata.has("coupons") && metadata.get("coupons").isArray()) {
                    int position = 1;
                    for (JsonNode coupon : metadata.get("coupons")) {
                        jdbcTemplate.update(
                                "INSERT INTO flight_coupons (transaction_id, position, carrier, flight_number, from_code, to_code, departure, arrival) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                transactionId, position++, coupon.hasNonNull("carrier") ? coupon.get("carrier").asText() : null,
                                coupon.get("flight_number").asText(), coupon.get("from").asText(), coupon.get("to").asText(),
                                OffsetDateTime.parse(coupon.get("departure").asText()), OffsetDateTime.parse(coupon.get("arrival").asText())
                        );
                    }
                }
            }
            case "hotel" -> {
                JsonNode address = metadata.get("address");
                jdbcTemplate.update(
                        "INSERT INTO hotel_stays (transaction_id, name, street, city, country, check_in, check_out, nights, room_rate_per_night) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        transactionId, metadata.get("name").asText(), address.get("street").asText(), address.get("city").asText(),
                        address.get("country").asText(), java.sql.Date.valueOf(metadata.get("check_in").asText()),
                        java.sql.Date.valueOf(metadata.get("check_out").asText()), metadata.get("nights").asInt(),
                        Money.of(new BigDecimal(metadata.get("room_rate_per_night").asText()))
                );
                if (metadata.has("extras") && metadata.get("extras").isArray()) {
                    for (JsonNode extra : metadata.get("extras")) {
                        jdbcTemplate.update(
                                "INSERT INTO hotel_extras (transaction_id, name, amount) VALUES (?, ?, ?)",
                                transactionId, extra.get("name").asText(), Money.of(new BigDecimal(extra.get("amount").asText()))
                        );
                    }
                }
            }
            case "rail" -> {
                if (metadata.has("legs") && metadata.get("legs").isArray()) {
                    int position = 1;
                    for (JsonNode leg : metadata.get("legs")) {
                        jdbcTemplate.update(
                                "INSERT INTO rail_legs (transaction_id, position, origin, destination, departure, arrival, train_number, travel_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                transactionId, position++, leg.get("origin").asText(), leg.get("destination").asText(),
                                OffsetDateTime.parse(leg.get("departure").asText()), OffsetDateTime.parse(leg.get("arrival").asText()),
                                leg.hasNonNull("train_number") ? leg.get("train_number").asText() : null,
                                leg.hasNonNull("class") ? leg.get("class").asText() : null
                        );
                    }
                }
            }
            case "navan_fee" -> jdbcTemplate.update(
                    "INSERT INTO navan_fee_details (transaction_id, fee_kind, description, related_external_id) VALUES (?, ?, ?, ?)",
                    transactionId, metadata.get("fee_kind").asText(),
                    metadata.hasNonNull("description") ? metadata.get("description").asText() : null,
                    metadata.hasNonNull("related_external_id") ? metadata.get("related_external_id").asText() : null
            );
            default -> throw ApiException.badRequest("unknown transaction type: " + type);
        }
    }

    private void clearTypeSpecificRows(String transactionId) {
        jdbcTemplate.update("DELETE FROM tax_lines WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM fee_lines WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM flight_details WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM flight_coupons WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM hotel_stays WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM hotel_extras WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM rail_legs WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM navan_fee_details WHERE transaction_id = ?", transactionId);
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("unable to serialize metadata");
        }
    }
}