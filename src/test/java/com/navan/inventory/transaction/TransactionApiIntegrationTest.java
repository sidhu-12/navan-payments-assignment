package com.navan.inventory.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.navan.inventory.support.FixtureLoader.loadSeed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionApiIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM invoice_lines");
        jdbcTemplate.execute("DELETE FROM invoice_tax_rollups");
        jdbcTemplate.execute("DELETE FROM invoice_fee_rollups");
        jdbcTemplate.execute("DELETE FROM invoices");
        jdbcTemplate.execute("DELETE FROM transactions");
    }

    // Required case 1: create all transaction types and round-trip their metadata.
    @Test
    void requiredCase01_createsAndReadsAllTransactionTypes() throws Exception {
        JsonNode flight = getTransaction(createTransaction(loadSeed("flight")));
        JsonNode hotel = getTransaction(createTransaction(loadSeed("hotel")));
        JsonNode rail = getTransaction(createTransaction(loadSeed("rail")));
        JsonNode tripFee = getTransaction(createTransaction(loadSeed("trip_fee")));
        JsonNode agentFee = getTransaction(createTransaction(loadSeed("agent_call_fee")));

        assertThat(flight.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(hotel.get("metadata").get("name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(hotel.get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(rail.get("metadata").get("legs").size()).isEqualTo(2);
        assertThat(tripFee.get("metadata").get("fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(agentFee.get("metadata").get("fee_kind").asText()).isEqualTo("agent_call_fee");
    }

    // Required case 2: duplicate external_id returns the original ID without adding a row.
    @Test
    void requiredCase02_duplicateExternalIdReturnsExistingId() throws Exception {
        String originalId = createTransaction(loadSeed("flight"));
        MvcResult duplicate = mockMvc.perform(post("/transactions")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loadSeed("duplicate_flight"))))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode error = objectMapper.readTree(duplicate.getResponse().getContentAsString());
        assertThat(error.get("error").asText()).contains("duplicate external_id");
        assertThat(error.get("id").asText()).isEqualTo(originalId);
        assertThat(transactionCount("flight-TXL-LHR-2026-03-11")).isEqualTo(1);
    }

    // Required case 3: invoice copied lines and grouped totals match the trip fixtures.
    @Test
    void requiredCase03_invoiceTotalsAndRollupsMatchFixtures() throws Exception {
        JsonNode invoice = getInvoice(createTripInvoice());

        assertThat(invoice.get("currency").asText()).isEqualTo("EUR");
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo("685.70");
        assertThat(invoice.get("line_count").asInt()).isEqualTo(5);
        assertThat(invoice.get("totals").get("flight").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.get("totals").get("hotel").decimalValue()).isEqualByComparingTo("331.70");
        assertThat(invoice.get("totals").get("rail").decimalValue()).isEqualByComparingTo("94.00");
        assertThat(invoice.get("totals").get("trip_fee").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(invoice.get("totals").get("agent_call_fee").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(invoice.get("tax_rollup").toString()).contains("Airport tax", "VAT");
        assertThat(invoice.get("fee_rollup").toString()).contains("YQ", "Booking fee");
    }

    // Required case 4: direct source-row mutation cannot change a persisted invoice snapshot.
    @Test
    void requiredCase04_invoiceSnapshotStaysFrozenAfterTransactionMutation() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        String invoiceId = postInvoice(flightId);
        jdbcTemplate.update("UPDATE transactions SET total = ? WHERE id = ?", new BigDecimal("999.99"), flightId);

        JsonNode invoice = getInvoice(invoiceId);
        assertThat(invoice.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoiceLineTotal(invoice, "flight")).isEqualByComparingTo("220.00");
    }

    // Required case 5: transactions in different currencies cannot share one invoice.
    @Test
    void requiredCase05_mixedCurrencyInvoiceIsRejected() throws Exception {
        String eurId = createTransaction(loadSeed("flight"));
        Map<String, Object> usdHotel = loadSeed("hotel");
        usdHotel.put("currency", "USD");
        String usdId = createTransaction(usdHotel);

        mockMvc.perform(post("/invoices")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("transaction_ids", List.of(eurId, usdId)))))
                .andExpect(status().isConflict());
    }

    // Required case 6: overlapping duplicate POSTs leave one row and return the same ID.
    @Test
    void requiredCase06_concurrentDuplicatePostsPersistOneTransaction() throws Exception {
        Map<String, Object> payload = loadSeed("flight");
        String externalId = "concurrent-flight-" + UUID.randomUUID();
        payload.put("external_id", externalId);
        String body = json(payload);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> {
                start.await();
                return mockMvc.perform(post("/transactions").contentType(APPLICATION_JSON).content(body)).andReturn();
            };
            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            start.countDown();
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();

            assertThat(firstResult.getResponse().getStatus()).isIn(200, 201, 409);
            assertThat(secondResult.getResponse().getStatus()).isIn(200, 201, 409);
            assertThat(transactionCount(externalId)).isEqualTo(1);
            assertThat(responseId(firstResult)).isEqualTo(responseId(secondResult));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentTransactionPatchAndInvoiceCreateRemainConsistent() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        Map<String, Object> updatedFlight = loadSeed("flight");
        updatedFlight.put("total", 999.99);
        String patchBody = json(updatedFlight);
        String invoiceBody = json(Map.of("transaction_id", flightId));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> patchRequest = () -> {
                start.await();
                return mockMvc.perform(patch("/transactions/{id}", flightId)
                                .contentType(APPLICATION_JSON).content(patchBody))
                        .andReturn();
            };
            Callable<MvcResult> invoiceRequest = () -> {
                start.await();
                return mockMvc.perform(post("/invoices")
                                .contentType(APPLICATION_JSON).content(invoiceBody))
                        .andReturn();
            };
            Future<MvcResult> patchFuture = executor.submit(patchRequest);
            Future<MvcResult> invoiceFuture = executor.submit(invoiceRequest);
            start.countDown();

            MvcResult patchResult = patchFuture.get();
            MvcResult invoiceResult = invoiceFuture.get();
            assertThat(patchResult.getResponse().getStatus()).isIn(200, 409);
            assertThat(invoiceResult.getResponse().getStatus()).isEqualTo(200);
            BigDecimal snapshotTotal = getJson(invoiceResult).get("grand_total").decimalValue();
            if (patchResult.getResponse().getStatus() == 200) {
                assertThat(snapshotTotal).isEqualByComparingTo("999.99");
                assertThat(transactionTotal(flightId)).isEqualByComparingTo("999.99");
            } else {
                assertThat(snapshotTotal).isEqualByComparingTo("220.00");
                assertThat(transactionTotal(flightId)).isEqualByComparingTo("220.00");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // Required case 7: PATCH on a billed transaction is rejected without mutating it.
    @Test
    void requiredCase07_patchOfBilledTransactionIsRejected() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        String invoiceId = postInvoice(flightId);
        Map<String, Object> updatedFlight = loadSeed("flight");
        updatedFlight.put("total", 999.99);

        mockMvc.perform(patch("/transactions/{id}", flightId)
                        .contentType(APPLICATION_JSON)
                        .content(json(updatedFlight)))
                .andExpect(status().isConflict());

        assertThat(transactionTotal(flightId)).isEqualByComparingTo("220.00");
        assertThat(getInvoice(invoiceId).get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
    }

    // Required case 8: DELETE on a billed transaction is rejected and preserves the row.
    @Test
    void requiredCase08_deleteOfBilledTransactionIsRejected() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        postInvoice(flightId);

        mockMvc.perform(delete("/transactions/{id}", flightId)).andExpect(status().isConflict());
        assertThat(getTransaction(flightId).get("id").asText()).isEqualTo(flightId);
    }

    // Required case 9: deleting an invoice removes its snapshot but preserves source transactions.
    @Test
    void requiredCase09_deleteInvoicePreservesTransactions() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        String hotelId = createTransaction(loadSeed("hotel"));
        String invoiceId = postInvoice(flightId, hotelId);

        mockMvc.perform(delete("/invoices/{id}", invoiceId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/invoices/{id}", invoiceId)).andExpect(status().isNotFound());
        assertThat(getTransaction(flightId).get("id").asText()).isEqualTo(flightId);
        assertThat(getTransaction(hotelId).get("id").asText()).isEqualTo(hotelId);
    }

    // Required case 10: transaction list uses its default bound and rejects oversized limits.
    @Test
    void requiredCase10_listsUseBoundedPagination() throws Exception {
        createTransaction(loadSeed("flight"));
        String invoiceId = postInvoice(createTransaction(loadSeed("hotel")));

        JsonNode transactions = getJson(mockMvc.perform(get("/transactions")).andExpect(status().isOk()).andReturn());
        assertThat(transactions.get("limit").asInt()).isEqualTo(50);
        assertThat(transactions.get("items").size()).isEqualTo(2);
        mockMvc.perform(get("/transactions").param("limit", "1000")).andExpect(status().isBadRequest());

        JsonNode invoices = getJson(mockMvc.perform(get("/invoices")).andExpect(status().isOk()).andReturn());
        assertThat(invoices.get("limit").asInt()).isEqualTo(50);
        assertThat(invoices.get("items").size()).isEqualTo(1);
        assertThat(invoices.get("items").get(0).get("id").asText()).isEqualTo(invoiceId);
    }

    // Additional coverage: invoice PATCH atomically replaces its snapshot with current selections.
    @Test
    void invoicePatchRebuildsSnapshot() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        String hotelId = createTransaction(loadSeed("hotel"));
        String invoiceId = postInvoice(flightId, hotelId);

        MvcResult response = mockMvc.perform(patch("/invoices/{id}", invoiceId)
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("transaction_ids", List.of(flightId)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode invoice = getJson(response);
        assertThat(invoice.get("transaction_ids").size()).isEqualTo(1);
        assertThat(invoice.get("transaction_ids").get(0).asText()).isEqualTo(flightId);
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
    }

    private String createTripInvoice() throws Exception {
        String flightId = createTransaction(loadSeed("flight"));
        String hotelId = createTransaction(loadSeed("hotel"));
        String railId = createTransaction(loadSeed("rail"));
        String tripFeeId = createTransaction(loadSeed("trip_fee"));
        String agentFeeId = createTransaction(loadSeed("agent_call_fee"));
        return postInvoice(flightId, hotelId, railId, tripFeeId, agentFeeId);
    }

    private String createTransaction(Map<String, Object> payload) throws Exception {
        MvcResult response = mockMvc.perform(post("/transactions")
                        .contentType(APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return responseId(response);
    }

    private String postInvoice(String... ids) throws Exception {
        MvcResult response = mockMvc.perform(post("/invoices")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("transaction_ids", List.of(ids)))))
                .andExpect(status().isOk())
                .andReturn();
        return responseId(response);
    }

    private JsonNode getTransaction(String id) throws Exception {
        return getJson(mockMvc.perform(get("/transactions/{id}", id)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode getInvoice(String id) throws Exception {
        return getJson(mockMvc.perform(get("/invoices/{id}", id)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode getJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String responseId(MvcResult result) throws Exception {
        return getJson(result).get("id").asText();
    }

    private Integer transactionCount(String externalId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE external_id = ?", Integer.class, externalId);
    }

    private BigDecimal transactionTotal(String id) {
        return jdbcTemplate.queryForObject("SELECT total FROM transactions WHERE id = ?", BigDecimal.class, id);
    }

    private BigDecimal invoiceLineTotal(JsonNode invoice, String type) {
        for (JsonNode line : invoice.get("lines")) {
            if (type.equals(line.get("type").asText())) {
                return line.get("total").decimalValue();
            }
        }
        throw new AssertionError("Invoice line not found for type: " + type);
    }
}