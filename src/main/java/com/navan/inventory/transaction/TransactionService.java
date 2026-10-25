package com.navan.inventory.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.inventory.common.Ids;
import com.navan.inventory.common.ListEnvelope;
import com.navan.inventory.common.NavanFeeKind;
import com.navan.inventory.common.Pagination;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TransactionService {

    private final TransactionJdbcRepository repository;

    /** Creates a transaction service backed by the JDBC repository. */
    public TransactionService(TransactionJdbcRepository repository) {
        this.repository = repository;
    }

    /**
     * Validates and atomically stores a transaction and its child records.
     *
     * @param request transaction fields and type-specific metadata
     * @return the persisted transaction
     * @throws ApiException if the request is invalid or its external ID already exists
     */
    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        validateRequest(request);
        String type = normalizeType(request.type());
        String currency = normalizeCurrency(request.currency());
        String externalId = request.externalId().trim();
        validateMetadata(type, request.metadata());

        String id = Ids.newId();
        try {
            repository.insert(id, request, type, currency, externalId);
            return getById(id);
        } catch (DuplicateKeyException e) {
            String existingId = repository.findIdByExternalId(externalId);
            throw ApiException.conflict("duplicate external_id: " + externalId, existingId);
        }
    }

    /**
     * Returns one bounded page of transactions.
     *
     * @param limit requested page size, defaulting to the configured default
     * @param offset number of records to skip
     * @return page contents and pagination metadata
     * @throws ApiException if the pagination values are outside allowed bounds
     */
    public ListEnvelope<TransactionResponse> list(Integer limit, Integer offset) {
        Pagination pagination = Pagination.from(limit, offset);
        long total = repository.count();
        List<TransactionResponse> items = repository.findPage(pagination.limit(), pagination.offset());
        return new ListEnvelope<>(items, pagination.limit(), pagination.offset(), total);
    }

    /**
     * Retrieves one transaction by its server-generated ID.
     *
     * @param id transaction ID
     * @return the stored transaction including taxes, fees, and metadata
     * @throws ApiException if the ID is blank or no transaction exists
     */
    public TransactionResponse getById(String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("transaction id is required");
        }
        Optional<TransactionResponse> transaction = repository.findById(id);
        if (transaction.isEmpty()) {
            throw ApiException.notFound("transaction not found: " + id);
        }
        return transaction.get();
    }

    /**
     * Replaces an unbilled transaction and its child records atomically.
     *
     * @param id transaction ID
     * @param request replacement transaction fields
     * @return the updated transaction
     * @throws ApiException if the transaction is missing, the request is invalid, or the transaction is already invoiced
     */
    @Transactional
    public TransactionResponse update(String id, TransactionRequest request) {
        validateRequest(request);
        repository.lockById(id);
        getById(id);
        if (isInvoiced(id)) {
            throw ApiException.conflict("transaction is already included in an invoice");
        }
        String type = normalizeType(request.type());
        String currency = normalizeCurrency(request.currency());
        validateMetadata(type, request.metadata());

        repository.update(id, request, type, currency);
        return getById(id);
    }

    /**
     * Deletes an unbilled transaction; its dependent live child rows are removed by database cascades.
     *
     * @param id transaction ID
     * @throws ApiException if the transaction is missing or already invoiced
     */
    @Transactional
    public void delete(String id) {
        repository.lockById(id);
        getById(id);
        if (isInvoiced(id)) {
            throw ApiException.conflict("transaction is already included in an invoice");
        }
        repository.delete(id);
    }

    /**
     * Locks the requested transaction rows and returns their current snapshots in input order.
     * Missing IDs are omitted. The caller must have an active transaction so the locks remain held
     * while it creates or rebuilds an invoice.
     *
     * @param ids transaction IDs to lock and snapshot
     * @return snapshots for the IDs that currently exist
     */
    public List<TransactionSnapshot> lockSnapshots(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.lockSnapshots(ids);
    }

    private boolean isInvoiced(String transactionId) {
        return repository.isInvoiced(transactionId);
    }

    private void validateRequest(TransactionRequest request) {
        if (request == null) {
            throw ApiException.badRequest("transaction payload is required");
        }
        if (request.externalId() == null || request.externalId().isBlank()) {
            throw ApiException.badRequest("external_id is required");
        }
        if (request.type() == null || request.type().isBlank()) {
            throw ApiException.badRequest("type is required");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw ApiException.badRequest("currency is required");
        }
        if (request.occurredAt() == null) {
            throw ApiException.badRequest("occurred_at is required");
        }
        if (request.total() == null) {
            throw ApiException.badRequest("total is required");
        }
    }

    private String normalizeType(String type) {
        String normalized = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            throw ApiException.badRequest("type is required");
        }
        if (!normalized.equals("flight") && !normalized.equals("hotel") && !normalized.equals("rail") && !normalized.equals("navan_fee")) {
            throw ApiException.badRequest("unknown transaction type: " + normalized);
        }
        return normalized;
    }

    private String normalizeCurrency(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private void validateMetadata(String type, JsonNode metadata) {
        if (metadata == null || metadata.isNull() || metadata.isEmpty()) {
            throw ApiException.badRequest("metadata is required");
        }
        switch (type) {
            case "flight" -> {
                if (!metadata.hasNonNull("origin") || !metadata.hasNonNull("destination") || !metadata.hasNonNull("fare")) {
                    throw ApiException.badRequest("flight metadata requires origin, destination, and fare");
                }
                if (!metadata.has("coupons")) {
                    throw ApiException.badRequest("flight metadata requires coupons");
                }
            }
            case "hotel" -> {
                JsonNode address = metadata.get("address");
                if (!metadata.hasNonNull("name") || address == null || !address.hasNonNull("street") || !address.hasNonNull("city") || !address.hasNonNull("country")) {
                    throw ApiException.badRequest("hotel metadata requires name and address");
                }
                if (!metadata.hasNonNull("check_in") || !metadata.hasNonNull("check_out") || !metadata.hasNonNull("nights") || !metadata.hasNonNull("room_rate_per_night")) {
                    throw ApiException.badRequest("hotel metadata requires stay details");
                }
            }
            case "rail" -> {
                if (!metadata.has("legs") || !metadata.get("legs").isArray() || metadata.get("legs").size() == 0) {
                    throw ApiException.badRequest("rail metadata requires legs");
                }
            }
            case "navan_fee" -> {
                if (!metadata.hasNonNull("fee_kind")) {
                    throw ApiException.badRequest("navan_fee metadata requires fee_kind");
                }
                try {
                    NavanFeeKind.fromWire(metadata.get("fee_kind").asText());
                } catch (IllegalArgumentException ex) {
                    throw ApiException.badRequest("unknown navan_fee.fee_kind: " + metadata.get("fee_kind").asText());
                }
            }
            default -> throw ApiException.badRequest("unknown transaction type: " + type);
        }
    }

}
