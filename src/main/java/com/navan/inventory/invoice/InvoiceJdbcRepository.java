package com.navan.inventory.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.inventory.common.FeeLineDto;
import com.navan.inventory.common.Ids;
import com.navan.inventory.common.Money;
import com.navan.inventory.common.TaxLineDto;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Repository
class InvoiceJdbcRepository {

    private static final String INVOICE_COLUMNS = "id, currency, grand_total, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    InvoiceJdbcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void insert(String invoiceId, String currency, BigDecimal grandTotal, List<TransactionSnapshot> snapshots) {
        jdbcTemplate.update(
                "INSERT INTO invoices (id, currency, grand_total, created_at, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                invoiceId, currency, Money.of(grandTotal)
        );
        insertLines(invoiceId, snapshots);
        insertTaxRollups(invoiceId, snapshots);
        insertFeeRollups(invoiceId, snapshots);
    }

    void replace(String invoiceId, String currency, BigDecimal grandTotal, List<TransactionSnapshot> snapshots) {
        jdbcTemplate.update("DELETE FROM invoice_lines WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM invoice_tax_rollups WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM invoice_fee_rollups WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update(
                "UPDATE invoices SET currency = ?, grand_total = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                currency, Money.of(grandTotal), invoiceId
        );
        insertLines(invoiceId, snapshots);
        insertTaxRollups(invoiceId, snapshots);
        insertFeeRollups(invoiceId, snapshots);
    }

    long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Long.class);
        return count == null ? 0 : count;
    }

    List<InvoiceResponse> findPage(int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + INVOICE_COLUMNS + " FROM invoices ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapInvoiceRow, limit, offset
        );
    }

    InvoiceResponse findById(String invoiceId) {
        List<InvoiceResponse> rows = jdbcTemplate.query(
                "SELECT " + INVOICE_COLUMNS + " FROM invoices WHERE id = ?", this::mapInvoiceRow, invoiceId
        );
        return rows.stream().findFirst().orElse(null);
    }

    boolean lockById(String invoiceId) {
        return !jdbcTemplate.query("SELECT id FROM invoices WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("id"), invoiceId).isEmpty();
    }

    void delete(String invoiceId) {
        jdbcTemplate.update("DELETE FROM invoices WHERE id = ?", invoiceId);
    }

    private void insertLines(String invoiceId, List<TransactionSnapshot> snapshots) {
        int position = 1;
        for (TransactionSnapshot snapshot : snapshots) {
            jdbcTemplate.update(
                    "INSERT INTO invoice_lines (id, invoice_id, transaction_id, type, total, tax_lines_json, fee_lines_json, metadata_json, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Ids.newId(), invoiceId, snapshot.id(), snapshot.type().wire(), snapshot.total(),
                    writeJson(snapshot.taxLines()), writeJson(snapshot.feeLines()), writeJson(snapshot.metadata()), position++
            );
        }
    }

    private InvoiceResponse mapInvoiceRow(ResultSet rs, int rowNum) throws SQLException {
        String invoiceId = rs.getString("id");
        return buildInvoiceResponse(invoiceId, rs.getString("currency"), rs.getBigDecimal("grand_total"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private InvoiceResponse buildInvoiceResponse(String invoiceId, String currency, BigDecimal grandTotal,
                                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT transaction_id, type, total, tax_lines_json, fee_lines_json, metadata_json FROM invoice_lines WHERE invoice_id = ? ORDER BY position",
                invoiceId
        );
        List<InvoiceLineResponse> lines = new ArrayList<>();
        List<String> transactionIds = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("grand_total", grandTotal);

        for (Map<String, Object> row : rows) {
            String transactionId = (String) row.get("transaction_id");
            String type = (String) row.get("type");
            BigDecimal total = (BigDecimal) row.get("total");
            transactionIds.add(transactionId);
            try {
                List<TaxLineDto> taxLines = objectMapper.readValue((String) row.get("tax_lines_json"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TaxLineDto.class));
                List<FeeLineDto> feeLines = objectMapper.readValue((String) row.get("fee_lines_json"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FeeLineDto.class));
                JsonNode metadata = objectMapper.readTree((String) row.get("metadata_json"));
                String totalKey = "navan_fee".equals(type) ? metadata.path("fee_kind").asText(type) : type;
                BigDecimal currentTotal = totals.get(totalKey);
                totals.put(totalKey, currentTotal == null ? total : currentTotal.add(total));
                lines.add(new InvoiceLineResponse(transactionId + "-line", transactionId, type, total, taxLines, feeLines, metadata));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to deserialize invoice line", e);
            }
        }

        return new InvoiceResponse(invoiceId, currency, grandTotal, transactionIds, lines.size(), lines,
                readTaxRollups(invoiceId), readFeeRollups(invoiceId), totals, createdAt, updatedAt);
    }

    private List<TaxRollup> readTaxRollups(String invoiceId) {
        return jdbcTemplate.query(
                "SELECT name, rate, amount FROM invoice_tax_rollups WHERE invoice_id = ? ORDER BY name, rate NULLS LAST",
                (rs, rowNum) -> new TaxRollup(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")),
                invoiceId
        );
    }

    private List<FeeRollup> readFeeRollups(String invoiceId) {
        return jdbcTemplate.query(
                "SELECT name, amount FROM invoice_fee_rollups WHERE invoice_id = ? ORDER BY name",
                (rs, rowNum) -> new FeeRollup(rs.getString("name"), rs.getBigDecimal("amount")),
                invoiceId
        );
    }

    private void insertTaxRollups(String invoiceId, List<TransactionSnapshot> snapshots) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (TransactionSnapshot snapshot : snapshots) {
            for (TaxLineDto line : snapshot.taxLines()) {
                if (line == null) continue;
                String key = line.name() + "::" + (line.rate() == null ? "null" : line.rate().toPlainString());
                BigDecimal current = totals.get(key);
                totals.put(key, current == null ? line.amount() : current.add(line.amount()));
            }
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            String[] parts = entry.getKey().split("::");
            jdbcTemplate.update(
                    "INSERT INTO invoice_tax_rollups (invoice_id, name, rate, amount) VALUES (?, ?, ?, ?)",
                    invoiceId, parts[0], "null".equals(parts[1]) ? null : new BigDecimal(parts[1]), entry.getValue()
            );
        }
    }

    private void insertFeeRollups(String invoiceId, List<TransactionSnapshot> snapshots) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (TransactionSnapshot snapshot : snapshots) {
            for (FeeLineDto line : snapshot.feeLines()) {
                if (line != null) {
                    BigDecimal current = totals.get(line.name());
                    totals.put(line.name(), current == null ? line.amount() : current.add(line.amount()));
                }
            }
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            jdbcTemplate.update(
                    "INSERT INTO invoice_fee_rollups (invoice_id, name, amount) VALUES (?, ?, ?)",
                    invoiceId, entry.getKey(), entry.getValue()
            );
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("unable to serialize invoice payload");
        }
    }
}