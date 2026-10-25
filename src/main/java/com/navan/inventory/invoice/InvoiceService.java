package com.navan.inventory.invoice;

import com.navan.inventory.common.Ids;
import com.navan.inventory.common.InvoiceMembership;
import com.navan.inventory.common.ListEnvelope;
import com.navan.inventory.common.Pagination;
import com.navan.inventory.common.TransactionQuery;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
public class InvoiceService implements InvoiceMembership {

    private final InvoiceJdbcRepository repository;
    private final TransactionQuery transactionQuery;

    public InvoiceService(InvoiceJdbcRepository repository, TransactionQuery transactionQuery) {
        this.repository = repository;
        this.transactionQuery = transactionQuery;
    }

    @Transactional
    public InvoiceResponse create(InvoiceRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invoice payload is required");
        }
        List<String> ids = request.normalizedIds();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("at least one transaction id is required");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw ApiException.conflict("duplicate transaction ids are not allowed in a single invoice");
        }

        List<TransactionSnapshot> snapshots = loadSnapshotsInInputOrder(ids);
        if (snapshots.size() != ids.size()) {
            throw ApiException.notFound("one or more transaction ids not found");
        }
        String currency = snapshots.getFirst().currency();
        if (snapshots.stream().anyMatch(snapshot -> !Objects.equals(snapshot.currency(), currency))) {
            throw ApiException.conflict("mixed currency transactions are not allowed in a single invoice");
        }

        String invoiceId = Ids.newId();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (TransactionSnapshot snapshot : snapshots) {
            grandTotal = grandTotal.add(snapshot.total());
        }
        repository.insert(invoiceId, currency, grandTotal, snapshots);
        return getById(invoiceId);
    }

    public ListEnvelope<InvoiceResponse> list(Integer limit, Integer offset) {
        Pagination pagination = Pagination.from(limit, offset);
        long total = repository.count();
        List<InvoiceResponse> items = repository.findPage(pagination.limit(), pagination.offset());
        return new ListEnvelope<>(items, pagination.limit(), pagination.offset(), total);
    }

    public InvoiceResponse getById(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            throw ApiException.badRequest("invoice id is required");
        }
        InvoiceResponse invoice = repository.findById(invoiceId);
        if (invoice == null) {
            throw ApiException.notFound("invoice not found: " + invoiceId);
        }
        return invoice;
    }

    @Transactional
    public InvoiceResponse patch(String invoiceId, InvoiceRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invoice payload is required");
        }
        repository.lockById(invoiceId);
        getById(invoiceId);
        List<String> ids = request.normalizedIds();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("at least one transaction id is required");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw ApiException.conflict("duplicate transaction ids are not allowed in a single invoice");
        }
        rebuildInvoice(invoiceId, ids);
        return getById(invoiceId);
    }

    @Transactional
    public void deleteInvoice(String invoiceId) {
        repository.lockById(invoiceId);
        getById(invoiceId);
        repository.delete(invoiceId);
    }

    @Override
    public boolean isInvoiced(String transactionId) {
        return repository.isInvoiced(transactionId);
    }

    private List<TransactionSnapshot> loadSnapshotsInInputOrder(List<String> ids) {
        return transactionQuery.lockSnapshots(ids);
    }

    private void rebuildInvoice(String invoiceId, List<String> ids) {
        List<TransactionSnapshot> snapshots = loadSnapshotsInInputOrder(ids);
        if (snapshots.size() != ids.size()) {
            throw ApiException.notFound("one or more transaction ids not found");
        }
        String currency = snapshots.getFirst().currency();
        if (snapshots.stream().anyMatch(snapshot -> !Objects.equals(snapshot.currency(), currency))) {
            throw ApiException.conflict("mixed currency transactions are not allowed in a single invoice");
        }

        BigDecimal grandTotal = BigDecimal.ZERO;
        for (TransactionSnapshot snapshot : snapshots) {
            grandTotal = grandTotal.add(snapshot.total());
        }
        repository.replace(invoiceId, currency, grandTotal, snapshots);
    }
}