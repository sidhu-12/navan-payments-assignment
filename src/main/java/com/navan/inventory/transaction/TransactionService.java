package com.navan.inventory.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.inventory.common.Ids;
import com.navan.inventory.common.ListEnvelope;
import com.navan.inventory.common.NavanFeeKind;
import com.navan.inventory.common.Pagination;
import com.navan.inventory.common.TransactionQuery;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TransactionService implements TransactionQuery {

    private final TransactionJdbcRepository repository;

    public TransactionService(TransactionJdbcRepository repository) {
        this.repository = repository;
    }

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

    public ListEnvelope<TransactionResponse> list(Integer limit, Integer offset) {
        Pagination pagination = Pagination.from(limit, offset);
        long total = repository.count();
        List<TransactionResponse> items = repository.findPage(pagination.limit(), pagination.offset());
        return new ListEnvelope<>(items, pagination.limit(), pagination.offset(), total);
    }

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

    @Transactional
    public void delete(String id) {
        repository.lockById(id);
        getById(id);
        if (isInvoiced(id)) {
            throw ApiException.conflict("transaction is already included in an invoice");
        }
        repository.delete(id);
    }

    @Override
    public Optional<TransactionSnapshot> findSnapshot(String id) {
        return repository.findSnapshot(id);
    }

    @Override
    public List<TransactionSnapshot> findSnapshots(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<TransactionSnapshot> snapshots = new java.util.ArrayList<>();
        for (String id : ids) {
            findSnapshot(id).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    @Override
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
