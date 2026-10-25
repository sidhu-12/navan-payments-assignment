package com.navan.inventory.invoice;

import com.navan.inventory.common.Ids;
import com.navan.inventory.common.ListEnvelope;
import com.navan.inventory.common.Pagination;
import com.navan.inventory.common.TransactionSnapshot;
import com.navan.inventory.common.error.ApiException;
import com.navan.inventory.transaction.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
public class InvoiceService {

    private final InvoiceJdbcRepository repository;
    private final TransactionService transactionService;

    /** Creates an invoice service using persisted transaction snapshots. */
    public InvoiceService(InvoiceJdbcRepository repository, TransactionService transactionService) {
        this.repository = repository;
        this.transactionService = transactionService;
    }

    /**
     * Creates an invoice from the current transaction records and persists copied line snapshots.
     *
     * @param request one transaction ID or an ordered list of transaction IDs
     * @return the newly persisted invoice with its lines and rollups
     * @throws ApiException if IDs are missing or duplicated, or if their currencies differ
     */
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

    /**
     * Returns one bounded page of invoices.
     *
     * @param limit requested page size, defaulting to the configured default
     * @param offset number of records to skip
     * @return page contents and pagination metadata
     * @throws ApiException if the pagination values are outside allowed bounds
     */
    public ListEnvelope<InvoiceResponse> list(Integer limit, Integer offset) {
        Pagination pagination = Pagination.from(limit, offset);
        long total = repository.count();
        List<InvoiceResponse> items = repository.findPage(pagination.limit(), pagination.offset());
        return new ListEnvelope<>(items, pagination.limit(), pagination.offset(), total);
    }

    /**
     * Retrieves an invoice and its persisted snapshot lines by ID.
     *
     * @param invoiceId invoice ID
     * @return the invoice header, snapshot lines, rollups, and totals
     * @throws ApiException if the ID is blank or no invoice exists
     */
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

    /**
     * Atomically replaces an invoice's transaction selection and rebuilds its snapshots from current records.
     *
     * @param invoiceId invoice ID
     * @param request replacement transaction selection
     * @return the rebuilt invoice
     * @throws ApiException if the invoice or a transaction is missing, IDs are duplicated, or currencies differ
     */
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

    /**
     * Deletes an invoice and its copied lines without deleting the underlying transactions.
     *
     * @param invoiceId invoice ID
     * @throws ApiException if the invoice does not exist
     */
    @Transactional
    public void deleteInvoice(String invoiceId) {
        repository.lockById(invoiceId);
        getById(invoiceId);
        repository.delete(invoiceId);
    }

    private List<TransactionSnapshot> loadSnapshotsInInputOrder(List<String> ids) {
        return transactionService.lockSnapshots(ids);
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