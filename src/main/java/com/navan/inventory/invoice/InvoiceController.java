package com.navan.inventory.invoice;

import com.navan.inventory.common.ListEnvelope;
import com.navan.inventory.common.Pagination;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public InvoiceResponse create(@RequestBody InvoiceRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable String id) {
        return service.getById(id);
    }

    @PatchMapping("/{id}")
    public InvoiceResponse patch(@PathVariable String id, @RequestBody InvoiceRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ListEnvelope<InvoiceResponse> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        Pagination.from(limit, offset);
        return service.list(limit, offset);
    }
}
