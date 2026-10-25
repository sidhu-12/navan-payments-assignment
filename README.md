# Travel Inventory API

Spring Boot API for persisted travel transactions and immutable invoice snapshots. It supports `flight`, `hotel`, `rail`, and `navan_fee` transactions, including separate tax and fee lines.

## Run

Requires Java 21 or newer. The API uses file-backed H2 at `./data/navan`.

```sh
./run.sh
```

After startup, run these directly in another terminal to create a transaction and confirm it appears in the paginated list:

```sh
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' -d '{"type":"navan_fee","external_id":"terminal-smoke-001","currency":"EUR","occurred_at":"2026-03-11T18:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee","description":"Terminal smoke test"}}'
curl -sS 'http://localhost:8080/transactions?limit=5&offset=0'
```

The POST returns the stored transaction and its generated ID; the GET response includes the new item.

Run the required integration tests with one command:

```sh
./test.sh
```

Equivalent direct command: `./mvnw test`.

### Test coverage

The integration tests are separate methods with `requiredCaseNN` names and matching numbered comments in [TransactionApiIntegrationTest.java](src/test/java/com/navan/inventory/transaction/TransactionApiIntegrationTest.java):

1. `requiredCase01_createsAndReadsAllTransactionTypes`: fixture-backed flight, hotel, rail, trip-fee, and agent-call-fee metadata round-trip.
2. `requiredCase02_duplicateExternalIdReturnsExistingId`: conflict response contains the original ID and only one row exists.
3. `requiredCase03_invoiceTotalsAndRollupsMatchFixtures`: five-line EUR invoice totals and separate tax/fee rollups.
4. `requiredCase04_invoiceSnapshotStaysFrozenAfterTransactionMutation`: direct transaction-table mutation leaves the copied invoice unchanged.
5. `requiredCase05_mixedCurrencyInvoiceIsRejected`: EUR and USD cannot be combined.
6. `requiredCase06_concurrentDuplicatePostsPersistOneTransaction`: overlapping POST requests produce one stored transaction and the same ID.
7. `requiredCase07_patchOfBilledTransactionIsRejected`: PATCH returns 409 and preserves transaction and invoice totals.
8. `requiredCase08_deleteOfBilledTransactionIsRejected`: DELETE returns 409 and preserves the transaction.
9. `requiredCase09_deleteInvoicePreservesTransactions`: deleting an invoice removes it without deleting source transactions.
10. `requiredCase10_listsUseBoundedPagination`: default page bounds and oversized transaction page rejection, plus invoice listing.

`invoicePatchRebuildsSnapshot` additionally verifies that invoice PATCH replaces the selected snapshot.

## API examples

Set the base URL and create each transaction type. Save each returned `id` for invoice requests.

```sh
BASE=http://localhost:8080

curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"flight","external_id":"flight-demo-001","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.00,"tax_lines":[{"name":"Airport tax","amount":15.00}],"fee_lines":[{"name":"YQ","amount":25.00}],"metadata":{"origin":"TXL","destination":"LHR","fare":180.00,"coupons":[{"carrier":"BA","flight_number":"BA982","from":"TXL","to":"LHR","departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]}}'

curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"hotel","external_id":"hotel-demo-001","currency":"EUR","occurred_at":"2026-03-11T15:00:00Z","total":331.70,"tax_lines":[{"name":"VAT","rate":0.07,"amount":21.70}],"fee_lines":[],"metadata":{"name":"Hotel Mitte London","address":{"street":"12 Kingsway","city":"London","country":"GB"},"check_in":"2026-03-11","check_out":"2026-03-13","nights":2,"room_rate_per_night":140.00,"extras":[{"name":"Breakfast","amount":30.00}]}}'

curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"rail","external_id":"rail-demo-001","currency":"EUR","occurred_at":"2026-03-13T09:00:00Z","total":94.00,"tax_lines":[],"fee_lines":[{"name":"Booking fee","amount":4.00}],"metadata":{"legs":[{"origin":"London St Pancras","destination":"Lille Europe","departure":"2026-03-13T09:00:00Z","arrival":"2026-03-13T10:30:00Z","train_number":"EST9108","class":"standard"},{"origin":"Lille Europe","destination":"Paris Nord","departure":"2026-03-13T11:00:00Z","arrival":"2026-03-13T12:05:00Z","train_number":"EST9122","class":"standard"}]}}'

curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"navan_fee","external_id":"trip-fee-demo-001","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":25.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"trip_fee","description":"Navan trip fee","related_external_id":"flight-demo-001"}}'

curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"navan_fee","external_id":"agent-fee-demo-001","currency":"EUR","occurred_at":"2026-03-11T18:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee","description":"Agent fee for support call","related_external_id":"hotel-demo-001"}}'
```

Posting the same `external_id` twice returns 409 with the existing `id`; the unique database index guarantees only one transaction is stored:

```sh
curl -i -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"navan_fee","external_id":"duplicate-demo-001","currency":"EUR","occurred_at":"2026-03-11T18:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee"}}'
curl -i -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d '{"type":"navan_fee","external_id":"duplicate-demo-001","currency":"EUR","occurred_at":"2026-03-11T18:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee"}}'
curl -sS "$BASE/transactions?limit=50&offset=0"
```

Read and list transactions (default page size 50, maximum 100):

```sh
curl -sS "$BASE/transactions/<transaction-id>"
curl -sS "$BASE/transactions"
curl -sS "$BASE/transactions?limit=20&offset=0"
```

PATCH uses the same complete transaction representation as create. Once invoiced, PATCH and DELETE return 409 and leave the transaction unchanged:

```sh
curl -i -X PATCH "$BASE/transactions/<transaction-id>" -H 'Content-Type: application/json' -d '{"type":"flight","external_id":"flight-demo-001","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.00,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":180.00,"coupons":[]}}'
curl -i -X DELETE "$BASE/transactions/<transaction-id>"
```

Create an invoice using transaction IDs returned above. Invoice totals use decimal arithmetic; mixed currencies and duplicate transaction IDs return 409.

```sh
curl -sS -X POST "$BASE/invoices" -H 'Content-Type: application/json' -d '{"transaction_ids":["<flight-id>","<hotel-id>","<rail-id>","<trip-fee-id>","<agent-call-fee-id>"]}'
curl -sS "$BASE/invoices/<invoice-id>"
curl -sS "$BASE/invoices?limit=50&offset=0"
curl -i -X PATCH "$BASE/invoices/<invoice-id>" -H 'Content-Type: application/json' -d '{"transaction_ids":["<flight-id>","<hotel-id>"]}'
curl -i -X DELETE "$BASE/invoices/<invoice-id>"
```

`GET /health` reports API health. Persistence, snapshot, and volume decisions are summarized in the database schema and service/repository boundaries.