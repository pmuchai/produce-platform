# Prime Root

A modular fresh-produce platform for branch operations, inventory, sales and
spoilage control. Phase 0 provides the backend foundation; Phase 1 provides
Branches and Access. Phase 2 adds Catalogue and Units, maintenance screens and
a shared, permission-aware workspace. Phase 3 adds branch inventory, receipts,
batch traceability, receipt purchase costs, spoilage, corrections and stock reporting.
Phase 4 adds branch-bound paid sales, customer selection, automatic FEFO stock
allocation, receipts, full-sale voids, gross profit and staff/branch sales reporting.
Phase 5 adds atomic inter-branch dispatch, explicit in-transit stock, final
arrival with shortages/damage, carrying-cost traceability and transfer history.
Phase 6 adds paid operating expenses, audited recording corrections and
currency-separated net-profit reporting after expenses and stock losses.

## WhatsApp sales reporting (opt-in V1)

The optional WhatsApp Cloud API adapter supports verified staff linking and one
typed, read-only `get_sales_summary` tool. It reuses `SalesReportService`; it
does not execute arbitrary SQL, use an LLM, or permit business mutations.
The tool is invoked in-process by the messaging worker and is also available
to signed-in staff at `GET /api/whatsapp/sales-summary?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&branchId=UUID`.
Dates and branch are optional; both omitted dates mean today UTC. Supplied
dates must satisfy the existing inclusive, maximum-366-day reporting policy.
This V1 does **not** expose a remote MCP protocol endpoint or MCP OAuth
discovery; an MCP transport can wrap this same tool in a later integration.

### Operator setup

WhatsApp is disabled by default. Create a Meta app with the WhatsApp use case,
start with its test phone number/allowed recipients, and set these environment
variables on the application process (or equivalent Spring properties):

| Environment variable | Value |
| --- | --- |
| `PLATFORM_WHATSAPP_ENABLED` | `true` |
| `PLATFORM_WHATSAPP_APPSECRET` | Meta app secret, 32 hexadecimal characters |
| `PLATFORM_WHATSAPP_VERIFYTOKEN` | Your own random webhook verification secret, at least 32 characters |
| `PLATFORM_WHATSAPP_ACCESSTOKEN` | Meta API token with `whatsapp_business_messaging`; use an appropriately managed system-user token beyond testing |
| `PLATFORM_WHATSAPP_PHONENUMBERID` | Meta's numeric phone number **ID**, not the displayed telephone number |
| `PLATFORM_WHATSAPP_BUSINESSNUMBER` | Bot's international telephone number, digits only, without `+` |
| `PLATFORM_WHATSAPP_APIVERSION` | An explicitly selected supported Graph version, e.g. `v24.0`; check Meta's current support schedule |
| `PLATFORM_WHATSAPP_ENCRYPTIONKEY` | Base64 encoding of 32 cryptographically random bytes |

Keep secrets out of Git and logs. Store the encryption key in your deployment
secret manager, separately from database backups; preserve it across restarts.
Changing this key makes existing link grants unreadable and requires staff to
sign in again and relink. Grant tokens are AES-256-GCM encrypted with their
staff identity bound as authenticated data. No new dependencies are required.
Invalid enabled configuration fails startup rather than accepting unsigned
callbacks or using incomplete credentials.

Expose `https://YOUR-APP/api/whatsapp/webhook` over public HTTPS and register
it in Meta's webhook configuration using your verification token. Subscribe
to the `messages` field. The GET challenge response is plain text; POST
callbacks require a valid `X-Hub-Signature-256` HMAC over the exact raw body.
Only messages addressed to the configured phone number ID are accepted.
Never expose PostgreSQL or a development Keycloak admin console publicly.

Keycloak's existing confidential `keycloak` registration must issue refresh
tokens and include `app_roles` in **refreshed ID tokens**, just as it does for
login. Client-secret basic/post authentication is supported. Access-token
role mapping is not required: the worker refreshes the linked user's grant
and validates the signed ID token's issuer, subject, client audience,
expiration, nonce when present, and permission claim on every report.
No ADMIN bot identity or permanent copied permission list is used.
Keycloak refresh-token rotation is supported and updated grants are persisted
before querying reports. Keycloak session expiry/revocation or unavailable
refresh grants requires a fresh browser login and renewed linking; offline
access is not requested. WhatsApp queries can keep an ordinary refreshable
Keycloak session active, independently of the browser's short authentication age.

### Staff workflow

1. Sign into Prime Root as approved staff and open **WhatsApp > Link reporting account**.
2. Request a link code, then send the displayed `LINK ...` message to the bot
   from your own WhatsApp account. Codes expire after ten minutes, are stored
   only as hashes, and can verify a sender only once.
3. Refresh the page, check the full verified number, and confirm it in the
   authenticated, CSRF-protected browser. A WhatsApp message alone does not
   enable reporting. A number already linked to another staff member cannot
   be claimed without unlinking first.
4. Send `What is the total amount sold today?`, `sales today`,
   `sales yesterday`, or `sales this week` (Monday through the message date).
5. Use **Unlink / cancel** to revoke the link or abandon a pending challenge.
   To renew expired access, sign out and sign in again before requesting a
   new code. Confirming a new link replaces the old number and grant.

Only `SALES_VIEW` grants branch-wide sales totals; `SALE_CREATE` alone
returns explicitly labelled **your sales only**. Local approval, active
status, security version and branch scope are checked each time. Disabling
a branch removes it from branch-scoped reporting. Staff security changes
invalidate the existing link until renewed. Old queued requests cannot
inherit a newly created link, even when its telephone number is unchanged.

Replies include UTC dates/scope, currency-separated **net sales**, sales
counts and refund counts. Amounts come from complete filtered report totals,
not a displayed page or model arithmetic. Backdated sales use their selected
business date; refunds use their actual posting date. Revenue is not profit.
No customer detail, staff-level breakdown, cost or profit data is returned.
No sales/refunds means an explicit empty result, not an invented currency
or a combined cross-currency zero.

### Delivery and operational limits

Callbacks are bounded to 128 KiB and 100 messages, durably deduplicated by
Meta message ID, and acknowledged before network/report processing.
The database-backed worker claims jobs with renewable-by-recovery leases;
each sender is limited to ten queued messages per rolling minute.
Only recognized command identifiers and link-code hashes are stored, not
raw chat text or financial replies. Message records expire after 30 days.
Link requests, verification, confirmation, unlinking and sales queries are
audited without token values.

Outbound calls have connection/request timeouts. Identity-service transient
failures and explicit Meta 429 responses retry at most three attempts with
backoff and fresh authorization. A send is durably marked `SENDING` before
network I/O. Network ambiguity, Meta 5xx, or a crash during sending becomes
`UNCERTAIN` and is **not** automatically resent: exactly-once delivery cannot
be guaranteed by the external API. `SENT` means Meta accepted the send, not
that the handset received it. Inspect `whatsapp_messages.status` for
`FAILED`, `UNCERTAIN` and rate-limited `DROPPED` jobs when diagnosing delivery.

No reply is sent for messages already outside Meta's 24-hour service window.
This version sends only prompt-triggered, free-form replies, not scheduled
updates or templates. Unknown prompts receive fixed help, not AI-generated
answers. Number control is verified during linking; unlink promptly if a
phone is lost, shared or reassigned. Reports necessarily travel through Meta
and WhatsApp, so obtain appropriate staff consent and follow your business
data-handling policy.

Automated tests use the signed local OIDC provider with rotating refresh
tokens and a mocked Meta transport, including a real Chrome linking workflow.
Live delivery still requires the operator's Meta credentials, registered
webhook and phone-number setup. See [Meta's setup guide](https://developers.facebook.com/documentation/business-messaging/whatsapp/get-started)
and [messaging policy](https://business.whatsapp.com/policy).

## Requirements

- JDK 25 with `JAVA_HOME` configured, and the included Maven Wrapper.
- PostgreSQL for local development; Docker is not required.
- Keycloak, with the configuration below.
- H2 and a signed local OIDC provider are used only by automated tests and
  require no separate installation.

## Identity and permissions

Keycloak owns credentials, account authentication, job-function roles and
permission assignments. This application has no local passwords, role tables,
role-management API or password-reset endpoint. Create `ACCOUNTS`, `FINANCE`,
`DIRECTOR`, `OPERATIONS` and other composite roles in Keycloak without changing
the application.

The application implements these capabilities:

| Client permission role | Capability |
| --- | --- |
| `BRANCH_VIEW` | List/read branches within the staff member's local scope |
| `BRANCH_MANAGE` | Update/deactivate branches within scope; create branches only with business-wide scope |
| `USER_VIEW` | List approved staff; business-wide scope required |
| `USER_MANAGE` | Approve/update/deactivate staff and assign local branch access; business-wide scope required |
| `AUDIT_VIEW` | Read the latest 100 administrative audit events; business-wide scope required |
| `CATALOGUE_VIEW` | View the shared product catalogue and calculate unit conversions |
| `CATALOGUE_MANAGE` | Maintain catalogue definitions and fixed packaging conversions; business-wide scope required |
| `STOCK_VIEW` | View inventory, stock reports, batches, movements and receiving pickers within branch scope |
| `STOCK_RECEIVE` | Post receipts into active branches within scope |
| `STOCK_SPOILAGE` | Record batch losses within branch scope |
| `STOCK_ADJUST` | Post signed corrections, reverse movements and configure stock minimums within scope |
| `TRANSFER_VIEW` | Read transfers touching an assigned active source or destination branch, including in-transit history and discrepancies |
| `TRANSFER_DISPATCH` | Dispatch from an assigned active source to a different active destination |
| `TRANSFER_RECEIVE` | Finally receive at an assigned active destination and account for permanent shortages/damage |
| `SALE_CREATE` | Post paid sales in exactly one assigned branch; quote totals and view own receipts/performance |
| `SALES_VIEW` | Read all sales and staff performance within assigned branch scope |
| `SALE_VOID` | Void a whole sale within scope after all goods are returned and a full manual refund is confirmed |
| `PROFIT_VIEW` | View purchase/carrying costs, gross profit and net profit including aggregated expenses and stock losses |
| `EXPENSE_VIEW` | View expense history within assigned branch scope |
| `EXPENSE_CREATE` | Record already-paid operating expenses in active branches within scope |
| `EXPENSE_VOID` | Correct an erroneous expense by an audited void within branch scope |

Permissions are independent: managing does not implicitly grant viewing.
Only these exact permission codes in the validated ID token's `app_roles`
array become authorities. A composite name such as `ADMIN` alone grants
nothing; its effective component permission roles must also appear in that
claim. Unknown roles are ignored, while missing or malformed `app_roles`
rejects login. An approved identity with an empty array may view only its own
profile and the permission catalogue.

Local staff records explicitly approve an immutable `(issuer, subject)` pair,
and retain a local username, display name, active flag, branch assignments and
`allBranches` scope. The Keycloak user ID is the token's subject. A verified
first login with at least one known application permission creates a pending
access request, not an approved staff account. No identity is matched by
email/username and no business access is automatically granted. `allBranches` grants
data scope, not permissions. A global `USER_MANAGE` holder can approve and
change staff scope but cannot change anyone's Keycloak roles or credentials.
Treat that permission as privileged; restrict its assignment in Keycloak.

Active branch-scoped staff need at least one assigned branch, and all their
assigned branches must be active when their record is created/updated.
Deactivating a branch immediately removes it from branch-scoped access.
Service methods enforce both permissions and scope.

New reports and business operations will add permission codes and service
checks when implemented. Creating a Keycloak role does not implement a new
report or make an arbitrary permission string enforceable.

## Configure Keycloak

For local development, start Keycloak on port 8081 and create:

1. Realm `produce-platform`.
2. OpenID Connect client `produce-platform`, with client authentication enabled,
   Standard Flow enabled, and PKCE method **S256**. Disable implicit flow and
   direct access grants. The application uses confidential-client
   `client_secret_basic` authentication plus Authorization Code and PKCE.
3. Exact valid redirect URI
   `http://localhost:8080/login/oauth2/code/keycloak` and exact valid post-logout
   redirect URI `http://localhost:8080/`. Do not use wildcards.
4. The eighteen client permission roles listed above. Create a composite `ADMIN`
   role containing all eighteen. Create other job-function composites from the
   relevant permissions and assign them to staff.
5. A **User Client Role** protocol mapper for client `produce-platform`, claim
   `app_roles`, JSON type `String`, multivalued, included in the **ID token**.
   Including it in access tokens is optional for this session-based backend.
   Ensure client role scope mappings allow the effective permission roles.
   If Full Scope Allowed is disabled, explicitly map the required roles.
6. A test staff user with a permanent Keycloak credential and the composite
   `ADMIN` assignment. Copy that user's **ID**, not a role ID or client ID.
   Use Keycloak's token evaluation to check that `app_roles` includes the eighteen
   effective permission codes.

**Upgrading from Phase 1:** add `CATALOGUE_VIEW` and `CATALOGUE_MANAGE` client
roles to the ADMIN composite and appropriate client role scope mappings.
Existing ADMIN assignments do not automatically acquire new capabilities just
because the application adds permission codes. Sign out and sign back in to
obtain the updated ID-token claims. Catalogue readers need only
`CATALOGUE_VIEW`; editing screens require that view permission plus
`CATALOGUE_MANAGE` and local business-wide scope.

**Upgrading from Phase 2:** add `STOCK_VIEW`, `STOCK_RECEIVE`,
`STOCK_SPOILAGE` and `STOCK_ADJUST` to the relevant Keycloak client composites
and scope mappings, then sign out and sign in afresh. No new local roles or
staff identities are needed. Inventory operators do not need catalogue
management, branch management or business-wide access. Include `STOCK_VIEW`
alongside their posting permissions for the inventory screens. Restrict
`STOCK_ADJUST` to staff authorized to correct balances and reverse postings.

**Upgrading from Phase 4:** add `TRANSFER_VIEW`, `TRANSFER_DISPATCH` and
`TRANSFER_RECEIVE` to appropriate Keycloak client composites and scope mappings,
then sign out and sign back in. Give transfer operators `TRANSFER_VIEW` alongside
their posting permission for the workspace screens. Dispatch authorization is
against the source; receipt authorization is against the destination. Dispatchers
can choose any active destination without receiving authority there; the
transfer picker exposes only destination IDs/names, not its inventory.

## Expenses and net profit (Phase 6)

Add `EXPENSE_VIEW`, `EXPENSE_CREATE` and `EXPENSE_VOID` to appropriate Keycloak
client composites and role scope mappings, including `ADMIN` where intended,
then sign in again. Expense entry/history needs `EXPENSE_VIEW`; recording
additionally needs `EXPENSE_CREATE`, and correction needs `EXPENSE_VOID`.
Permissions are independent. `PROFIT_VIEW` now also grants aggregated expense
amounts and net-profit reports, without granting detailed expense-history access.
There are no new local identities or configuration secrets.

Open **Expenses > Record & history** at `/ui/expenses`. Record payments already
completed manually: active branch, paid date in UTC, category (`RENT`,
`UTILITIES`, `TRANSPORT`, `WAGES`, `MAINTENANCE`, `OTHER`), description, optional
payee, positive amount, and cash/M-Pesa/bank payment method. The currency is
the configured inventory/sales currency and is snapshotted per record.
Amounts must fit its minor units exactly; excess decimals are rejected, not
silently rounded. Noncash payments require an uppercase-normalized reference,
unique per expense payment method across the business, including voided records.
One payment reference cannot be split across several expense records in this
release. No payment is collected or refunded by the application.

Do not record stock purchases, transfers or sale refunds as operating expenses:
the existing inventory/sales ledger already accounts for their relevant costs.
Expenses are immutable. To correct a recording error, void it with a reason
and re-enter the correct expense. Originals retain branch/staff snapshots,
paid date, description and payment details; voids retain actor, UTC timestamp
and reason. This is an administrative correction, **not a refund confirmation**.
Create and void requests retain their UUID retry identities, enforce actor and
payload fingerprints, and serialize within branch scope. A replay cannot double
post or double void. Both actions emit audit events.

History filters by branch, category and inclusive UTC dates, with 50 records
per page. It includes records paid in the interval **or voided in the interval**,
so corrections to older expenses remain visible.

**Reports > Net profit after expenses and losses**, `/ui/reports/net-profit`,
and the dashboard card require `PROFIT_VIEW`. The calculation is:

```text
Net profit = net sales - net COGS - net paid expenses
             - net spoilage cost - finalized transfer loss cost
```

Sales use their selected UTC sale date; voids use their actual posting dates. Expenses use the paid date;
expense voids reverse the amount on the correction's UTC date, not by rewriting
an older report. Transfer shortages/damage are attributed to the **source branch**
on final arrival, using the snapshotted loss cost. In-transit stock and successful
transfers create no revenue or expense. Spoilage reversals offset the original
loss; legacy known-cost losses use the existing prorated receipt-cost fallback.
Generic inventory adjustments remain in Stock movements and are not silently
classified as operating losses.

Results are separated by branch and currency; totals cover all filtered rows,
not only the current 100-row report page. Currencies are never combined or
converted. Unknown loss costs are counted and suppress net profit. A loss with
an unknown currency suppresses every currency's net-profit total in the selected
scope, and the affected branch's results, rather than inventing a conversion.
Displayed known-cost loss subtotals are not a complete valuation where costs
are unknown. Reversing an unknown-cost spoilage entry removes that unresolved
loss from the result.

This is **recorded operating net profit**, not a statutory income statement:
taxes, depreciation, financing, unpaid/accrued expenses and unrecorded costs
are excluded. There is no expense-approval workflow, expense refund flow,
payroll calculation, chart of accounts, general ledger or period closing.
Reconcile the figures and accounting treatment with the business before relying
on them. Gross-profit reports retain their existing meaning and exclude expenses.

| API | Purpose |
| --- | --- |
| `GET /api/expenses/entry` | Active scoped branches, currency and expense categories |
| `POST /api/expenses` | Record a paid expense with `requestId` |
| `GET /api/expenses` | Filtered, paginated expense history |
| `POST /api/expenses/{id}/void` | Correct an entry using `requestId` and reason |
| `GET /api/finance/reports/net-profit` | Scoped branch/currency results; `branchId`, `start`, `end`, `page` |

Back up PostgreSQL and rehearse migration **V10** on staging before deployment.
It adds expenses and an event view without changing existing stock/sale balances.
The migration interprets paid dates as UTC regardless of database session zone.
`ExpenseIntegrationTests` covers recording/validation, scope, corrections,
currency isolation, stock/sales/transfer loss integration and concurrent retries.
`ExpenseMigrationTests` checks the V9 upgrade and UTC semantics. The existing
Chrome workflow now records/voids an expense and visits the net-profit report.
Optional real PostgreSQL checks reuse the same cases:

```powershell
.\mvnw.cmd "-Dtest=ExpenseIntegrationTests,ExpenseMigrationTests,PostgresExpenseIntegrationTests" test
```

Actual PostgreSQL checks are skipped unless the isolated `PG_TEST_URL` test
configuration described below is supplied; H2 does not replace that verification.

## Inter-branch stock transfers (Phase 5)

Open **Stock transfers → Dispatch & history** (`/ui/transfers`) or
**In transit / receive** (`/ui/transfers?status=IN_TRANSIT`). The detail screen
(`/ui/transfers/{id}`) shows each source batch, classification snapshot, original
receipt/expiry date, destination batch, arrival quantities and discrepancy reason.

- Dispatch positive **base-unit** quantities from an active source to a different
  active destination. Unexpired batches are allocated FEFO (dated first, then
  receipt date/creation/ID). All product lines succeed together or roll back.
  Source quantity and carrying value leave immediately; destination stock does
  not exist until arrival. The transfer is explicitly `IN_TRANSIT`.
- Receive **once, finally**, accounting for every allocation exactly once:
  `receivedQuantity + shortageQuantity + damagedQuantity = dispatched quantity`.
  All values are nonnegative base-unit quantities. Any shortage/damage needs a
  nonblank reason. Zero received is valid for full loss. Shortages and damaged/
  rejected goods are permanent loss, not destination stock or future receivables.
  There are intentionally no drafts, partial receipts, cancellation, or reversal.
- Positive arrival creates one destination batch per source allocation, with
  the same product, lot and original receipt/expiry dates; arrival time is the
  receive operation timestamp. `source_batch_id` preserves the full chain through
  repeated transfers. Product/type/variety/grade/base-unit text is snapshotted on
  the source batch, copied to the destination and allocation (catalogue edits do
  not rewrite batch classifications or transfer history). Existing batches get
  current catalogue text at V8 upgrade; earlier catalogue edits cannot be
  reconstructed. The source batch retains original input-unit, conversion and
  purchase-cost snapshots. Destination input is base units, with its derived
  unit cost; exact carrying value is authoritative, not the rounded unit cost.
  Arrival can record now-expired or deactivated products without stranding a
  dispatched load; normal sale eligibility still excludes them.
- Carrying costs are moved, not purchased again. Received cost is proportional
  to dispatched cost, rounded in the original currency; loss absorbs the
  remaining cents. Full depletion/arrival carries the exact remaining value.
  Unknown legacy costs remain unknown and are never silently treated as zero.
  Costs and purchase unit costs are null in transfer JSON and absent in HTML
  without `PROFIT_VIEW`.
- Transfer list/read requires `TRANSFER_VIEW` and scope at either endpoint.
  Branch-scoped access excludes inactive branches; business-wide viewers retain
  history. Receipt needs an active destination, but does not require a still-active
  source, so already-dispatched goods can arrive after source deactivation.
- Branch locks, acquired in deterministic ID order, serialize against receipts,
  adjustments, spoilage and sales on both PostgreSQL and H2. Each posting has a
  globally unique request UUID and actor/payload fingerprint. An identical retry
  returns the existing result; changed payload/actor or an already-finalized
  receipt with a new UUID conflicts. Retry an uncertain result with unchanged
  details and the same UUID; check history before changing an uncertain request.
  Workspace IDs survive failed requests in the current page, not a page reload.
- `TRANSFER_OUT` and `TRANSFER_IN` are stock ledger movements, never sales or
  revenue/profit events, and cannot be reversed by the generic stock-reversal API
  or UI. Existing stock-loss reports remain net batch spoilage; permanent transit
  shortages/damage and their costs appear in **transfer detail/history**, not
  disguised as sales or duplicated as spoilage.
- Dispatch/receipt audit events target the transfer; discrepancy events target
  the immutable finalized allocation with its reason and loss quantities.

JSON endpoints (session authentication and CSRF required for POST):

| Method / path | Payload / result |
| --- | --- |
| `GET /api/transfers/entry` | Dispatch pickers (source scope, active destination directory, active products/base units) |
| `POST /api/transfers` | `{requestId, sourceId, destinationId, notes?, lines:[{productId, quantity}]}` → detail |
| `GET /api/transfers?status=IN_TRANSIT&page=0` | Scoped history, optionally `RECEIVED`; 50 transfers/page |
| `GET /api/transfers/{id}` | Scoped allocation/traceability detail |
| `POST /api/transfers/{id}/receive` | `{requestId, notes?, allocations:[{allocationId, receivedQuantity, shortageQuantity, damagedQuantity, reason?}]}` → final detail |

Dispatch accepts up to 100 product lines and 1000 source-batch allocations;
larger dispatches must be split. Quantities support six decimal places. As with
inventory and sales, response quantities/costs are decimal strings to avoid
browser floating-point precision loss.

Browser redirects need no permissive CORS configuration. No application CORS
policy or wildcard Web Origins is needed for this same-origin backend.
Keep the client secret private. Local HTTP is development-only; deployed
Keycloak and the application must use HTTPS and exact production URLs.

### Testing from a phone on the LAN

Connect the phone to the same trusted Wi-Fi/network and open the computer's
LAN address, for example `http://192.168.100.206:8080/`, not `localhost`.
Both the app and Keycloak must listen on a LAN-reachable interface, with
ports 8080 and 8081 allowed on the trusted private network.

Use one consistent Keycloak issuer reachable by both devices:

```powershell
$env:KEYCLOAK_ISSUER_URI = "http://192.168.100.206:8081/realms/produce-platform"
$env:SESSION_COOKIE_SECURE = "false"
```

In the `produce-platform` client, add the exact valid redirect URI
`http://192.168.100.206:8080/login/oauth2/code/keycloak` and valid post-logout
redirect URI `http://192.168.100.206:8080/`. Keep the existing localhost
application redirects if needed; the **Keycloak issuer** must remain consistent
regardless of which application address is used. Do not add wildcard redirects
or disable issuer validation.

Staff approval binds the exact `(issuer, subject)` pair. If the same existing
realm/users previously used a localhost issuer, changing to the LAN issuer
requires a **one-time, backed-up identity-issuer migration**, preserving staff
IDs, subjects, status and branch assignments, incrementing `security_version`
and `version`, and recording audit events. Verify the realm and user IDs are
unchanged before migrating; never automatically match users by subject alone,
email or username across issuers. Recreated realms/users need explicit new
approval instead. In-memory H2 loses approvals when its process ends.

HTTP is only for trusted local testing; it does not encrypt passwords,
sessions or business data. Production requires HTTPS and secure cookies.
Stock and sale request IDs use native `crypto.randomUUID()` when available,
or a cryptographically random UUID v4 via `crypto.getRandomValues()` on LAN
HTTP, where browsers do not expose `randomUUID()`. Retry IDs are retained.

## Local PostgreSQL and startup

Provision database `primeroot_platform` and a non-superuser role
`primeroot_app` with connection and schema-object creation privileges.
Resolve authentication failures in PostgreSQL rather than weakening
authentication. Check connectivity using pgAdmin or:

```powershell
psql -h localhost -p 5432 -U primeroot_app -d primeroot_platform -W -c "SELECT current_database(), current_user;"
```

Set variables in the same PowerShell terminal used to start the application:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/primeroot_platform"
$dbCredential = Get-Credential -UserName "primeroot_app" -Message "PostgreSQL application login"
$env:DB_USERNAME = $dbCredential.UserName
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:KEYCLOAK_ISSUER_URI = "http://localhost:8081/realms/produce-platform"
$env:KEYCLOAK_CLIENT_ID = "produce-platform"
$clientCredential = Get-Credential -UserName "produce-platform" -Message "Enter the Keycloak client secret as the password"
$env:KEYCLOAK_CLIENT_SECRET = $clientCredential.GetNetworkCredential().Password
```

`DB_URL`, `DB_USERNAME`, the issuer and client ID default to the values above.
Both secrets are required and have no production fallback. Spring Boot does
not automatically load `.env` files. All `.env` files, including `.env.example`,
are ignored; never commit secrets or shared IDE run configurations containing
them. IntelliJ uses the same environment variables with JDK 25.

### Approve the initial administrator

Bootstrap is explicit, opt-in local approval of a known Keycloak identity;
it does **not** create a Keycloak user or grant any Keycloak permission.
With the environment above configured:

```powershell
$env:BOOTSTRAP_ADMIN_ENABLED = "true"
$env:BOOTSTRAP_ADMIN_SUBJECT = Read-Host "Keycloak user ID assigned the ADMIN composite"
$env:BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:BOOTSTRAP_ADMIN_FULL_NAME = "Platform Administrator"
.\mvnw.cmd spring-boot:run
```

The username and name are local labels and need not match Keycloak's username.
Bootstrap creates active, business-wide staff access and records an audit
event. A repeated run for the same active global identity is a no-op; it never
rebinds, reactivates or elevates an existing restricted identity. Invalid or
conflicting settings fail startup explicitly. Enable bootstrap on only one
instance during initial setup.

After the first successful startup, stop with Ctrl+C, remove the bootstrap
settings, and restart normally:

```powershell
Remove-Item Env:BOOTSTRAP_ADMIN_ENABLED
Remove-Item Env:BOOTSTRAP_ADMIN_SUBJECT
Remove-Item Env:BOOTSTRAP_ADMIN_USERNAME
Remove-Item Env:BOOTSTRAP_ADMIN_FULL_NAME
.\mvnw.cmd spring-boot:run
```

Check `http://localhost:8080/actuator/health`; it should return `{"status":"UP"}`.
This checks infrastructure, including the database, not staff permission
configuration. Install and activate the Prime Root Keycloak theme below, then
open `http://localhost:8080/` in a browser. It starts Keycloak sign-in directly,
with credentials entered inside the green Prime Root design and its local
produce-distribution artwork featuring red bulb onions, tomatoes and capsicum. The
headline is **YOUR FRESH PRODUCE DISTRIBUTION NETWORK**, with growers, deliveries
and branches presented as one connected network. There is no intermediate application landing page.
Sign in and verify that `/home` displays your welcome
dashboard. `/api/auth/me` still exposes your local staff profile and the expected
effective permission codes. No password is
submitted to this application.

Remove secrets from the terminal environment when finished:

```powershell
Remove-Item Env:DB_PASSWORD
Remove-Item Env:KEYCLOAK_CLIENT_SECRET
Remove-Variable dbCredential, clientCredential
```

### Prime Root Keycloak login theme

The custom login theme lives in `keycloak\themes\prime-root` and is validated
against **Keycloak 26.8.1**. Install it into the Keycloak distribution:

```powershell
.\keycloak\Install-PrimeRootTheme.ps1 -KeycloakHome "C:\tools\keycloak-26.8.1"
```

The installer copies the theme and the original logo/produce artwork from the
application's canonical assets; no external image or font service is used.
It changes files only inside `themes\prime-root`; it does not authenticate to
Keycloak, change the realm/client, or modify the database.

In the Keycloak Admin Console select the `produce-platform` realm, then
**Realm settings > Themes > Login theme > prime-root**, and save. If the
`produce-platform` client's advanced settings specify a **Login theme**
override, select `prime-root` there as well (or remove the override to inherit
the realm theme). Start a fresh sign-in to verify the selection.
The application JAR alone cannot activate an identity-provider theme.

The theme extends `keycloak.v2` and overrides only the supported footer hook,
branding messages, and CSS. Keycloak retains its native credential forms,
error messages, password visibility, recovery, session handling, MFA setup and
challenge screens. No credential-handling JavaScript, copied authentication
templates, application password form, or login iframe is introduced.
Revalidate these flows when upgrading Keycloak, particularly if its layout
classes change. Production theme caching should stay enabled. While editing
the theme in development, the Keycloak options
`--spi-theme--static-max-age=-1 --spi-theme--cache-themes=false --spi-theme--cache-templates=false`
allow changes to be reloaded without stale templates/styles.

OIDC still redirects to the Keycloak origin: matching visuals do not hide
that security boundary or change callback/logout URIs. A shared-domain reverse
proxy is a separate deployment concern.

## API and session workflow

Spring Security performs OIDC discovery, code exchange, PKCE S256, signature,
issuer/audience, nonce and authorization-state validation. The browser retains
only a server session cookie; APIs do not accept local credentials or arbitrary
bearer tokens. API authentication failures return JSON instead of redirecting
to the identity provider.

| Method | Endpoint | Result |
| --- | --- | --- |
| GET | `/`, `/login` | Start Keycloak sign-in directly; authenticated staff redirect to `/home` |
| GET | `/oauth2/authorization/keycloak` | Initiate browser login |
| GET | `/login/oauth2/code/keycloak` | Provider callback; redirects to `/home` after approval |
| GET | `/home` | Welcome dashboard with permission- and scope-filtered summaries |
| GET | `/api/auth/csrf` | Public CSRF token, header name and parameter name |
| GET | `/api/auth/me` | Current local `staff` profile and effective `permissions` |
| POST | `/api/auth/logout` | CSRF-protected local logout; redirects through Keycloak logout to `/`, which starts a fresh branded Keycloak login |
| GET | `/api/permissions` | Authenticated capability catalogue, not local role management |
| GET | `/api/branches` | Branches visible to the current staff member |
| GET | `/api/branches/{id}` | Branch within scope |
| POST | `/api/branches` | `BRANCH_MANAGE` and global scope; 201 with Location |
| PUT | `/api/branches/{id}` | `BRANCH_MANAGE` within scope; requires the current `If-Match` ETag |
| GET | `/api/users` | `USER_VIEW` and global scope |
| GET | `/api/users/access-requests` | Pending verified identities for the configured issuer; `USER_VIEW` and global scope |
| POST | `/api/users/access-requests/{id}/approve` | `USER_MANAGE` and global scope; identity comes from the stored request, not submitted fields |
| POST | `/api/users` | `USER_MANAGE` and global scope; 201 with Location |
| PUT | `/api/users/{id}` | `USER_MANAGE` and global scope; requires the current `If-Match` ETag |
| GET | `/api/audit-events` | `AUDIT_VIEW` and global scope; latest 100 events |

Anonymous visits to `/home` or `/ui/**`, and expired workspace sessions, initiate
Keycloak login directly. APIs continue to return 401 instead of a login-page
redirect. The Keycloak theme provides the complete branded sign-in screen;
the application never collects passwords. Authorization Code + PKCE remains
unchanged. UI branding is **Prime Root**; existing technical application, realm and
client identifiers remain unchanged.

New eligible identities are redirected to the public informational
`/access-pending` page after authentication, without establishing an
authenticated application session. It explains the approval process and has
a retry sign-in button. Repeat attempts reuse the same pending identity and
do not create duplicate requests. Invalid tokens, missing/malformed role
claims, new identities without application permissions and deactivated staff
do not enter this queue. Existing approved staff continue to sign in normally,
including approved accounts with no effective permissions.

The left sidebar uses expandable parent sections. Click a parent (or use
Enter/Space on its heading) to reveal its children. The current section opens
automatically and its active child is highlighted.

| Parent | Children |
| --- | --- |
| Dashboard | Overview |
| Sales | New sale |
| Stock | Receive stock, Stock Movement |
| Reports | Sales and receipts, Daily sales, Product sales, Branch sales, Staff performance, Customer sales, Payments and refunds, Gross profit / COGS, Stock valuation, Stock losses, Stock levels, Stock movements, Audit activity |
| User management | Staff & branch assignments |
| Configurations | Overview, Catalogue and units, Branches |

Parent sections are omitted when the current permissions and staff scope do not
allow any of their business pages. A single-branch salesperson with only
`SALE_CREATE` sees **Dashboard**, **Sales** and their permitted **Reports**,
not Stock, User management or Configurations. The Sales parent appears only
when New sale is available; business-wide and multiple-branch staff cannot sell.
Children without the required permissions in otherwise accessible groups are
disabled or omitted, not functional links. These navigation rules do not replace
the existing server-side authorization checks.

User management includes staff approval and branch assignment
forms backed by the existing staff APIs. Catalogue maintenance has working
create/edit forms.

### Adding and maintaining branches

Open **Configurations > Branches**. `BRANCH_VIEW` is needed to open the page;
`BRANCH_MANAGE` enables editing within the staff member's branch scope.
Business-wide staff with both permissions also see the **Add / edit branch**
form immediately. Enter a unique code (2-32 letters, numbers, underscores or
hyphens, starting with a letter/number), name and optional address, keep
**Active branch** checked and click **Add branch**. Codes are stored uppercase.
The new branch is then available in staff assignment and stock receiving.

Use **Edit branch** to change details or activate/deactivate a branch.
Version-checked updates prevent overwriting another administrator's changes.
Deactivation immediately removes the branch from branch-scoped access and
blocks selling there. Branch-scoped managers can edit assigned branches, but
cannot create new branches. View-only staff see no maintenance controls.

### Approving sales users and assigning branches

Open **User management > Staff & branch assignments**. The directory requires
business-wide `USER_VIEW`; approval/editing also requires `USER_MANAGE` and
`BRANCH_VIEW` to load the branch choices.

Create the enabled user and password in Keycloak first and assign the relevant
`produce-platform` client permissions (`SALE_CREATE` for a salesperson).
Ask the user to sign in once: they see **Awaiting administrator approval**.
Their verified identity appears in **Pending access requests**.

Click **Review request**, confirm the suggested local username/name and choose
branch access. Keep **Single branch (required for sales)** and choose an active
branch for a salesperson, or choose **Business-wide** for an administrator.
Click **Approve access**. The user can then click **Check approval and sign in**
on their pending page. A salesperson's branch is derived automatically at checkout.
There is no user-ID copying, SQL or browser console in this normal onboarding flow.

The request's issuer and subject are immutable; approval binds those stored
values server-side, regardless of identity fields submitted by a client.
Approval does not grant Keycloak roles or reactivate disabled staff. Pending
identities are separate from approved staff and do not appear in staff counts.
Requests are created/approved with audit events. Concurrent/repeated first
logins are deduplicated, and duplicate approvals are rejected.

Optional manual pre-approval is still available through **Approve staff / sales
user** and the original API by entering a known Keycloak user ID. It also
completes a matching pending request. Username collisions require the
administrator to choose a different local username; identities are never
merged or rebound by name.

Use **Edit assignments** in the directory to change a staff member's name,
active status or branch scope. Usernames and Keycloak identities are immutable.
Edits use the record version; if another administrator changed it, reload the
record before trying again. Multiple assigned branches and business-wide scope
are supported for non-selling staff. Removing your own business-wide scope or
deactivating yourself remains blocked.

These forms approve application access only: they never create Keycloak users,
passwords or permission assignments. Give sellers only the capabilities they
need; `SALE_CREATE` includes their own receipts/performance, while `SALES_VIEW`
allows other staff's sales in scope and `PROFIT_VIEW` exposes financial reports.
Existing `STOCK_VIEW` also exposes receipt purchase costs. Role changes require
a fresh sign-in.

The dashboard defaults to Monday through today in **UTC**, with inclusive
start/end date controls (maximum 366 days, no future dates). Global `AUDIT_VIEW`
shows the actual number of audit events in that interval and the absolute
change against the preceding equal-length interval. Counts are not capped at
100; the report shows the most recent 100 matching events and the total count.
Sign-ins and administrative activity are explicitly not staff performance.
Sales trends compare net revenue with the preceding equal-length interval,
separately by currency. Branch/staff cards count actual attributed sales/refund
activity, and the gross-profit card requires `PROFIT_VIEW`. Sellers without
`SALES_VIEW` see only their own transactions. With `STOCK_VIEW`, real branch/product stock,
receipts, spoilage comparisons, low-stock alerts and expiry warnings replace
the inventory placeholders; no illustrative business figures are presented.

Visible/active branch totals with `BRANCH_VIEW`, approved/active staff totals
with global `USER_VIEW`, and catalogue totals with `CATALOGUE_VIEW` are labelled
as **current snapshots**, not historical balances. Date filters do not
pretend to reconstruct past activation or access state.
The UI uses the same authorized services
as the APIs, not role-name checks or merely hidden client-side widgets. Users
without eligible view permissions see an explicit empty state. Composite roles
control these summaries through their effective permission claims; management
permissions alone do not grant summary viewing.
The page includes CSRF-protected sign-out and does not expose tokens.

## Phase 2: Catalogue and Units

Open **Configurations > Catalogue and units** (`/ui/catalogue`).
Create a produce type first (for example, Tomatoes), optional varieties/grades
belonging to that type, then a product with a unique code and a base stock unit.
Search filters the catalogue tables. Editors support creation, update and
deactivation, show server validation errors, and preserve inputs after failure.
Names and codes are rendered as escaped text. No frontend build tool or
external CDN is needed; Thymeleaf renders HTML and local CSS/JavaScript
provides form submissions and the conversion calculator. JavaScript is
required for mutations; tables remain readable without it.

| Definition | Rules |
| --- | --- |
| Produce type | Configurable code/name; no hard-coded produce enum |
| Variety / grade | Configurable within one produce type; products select varieties, receiving lines select grades of the matching type |
| Product | Unique normalized code, name, type, optional variety and immutable base stock unit; no product-level grade |
| Mass unit | Factor relative to kilograms; KG = 1 and G = 0.001 |
| Count unit | Factor relative to pieces; PIECE = 1 |
| Packaging unit | No universal mass/count factor; CRATE and BAG are seeded without assumed weight |
| Fixed product packaging | Explicit positive base-stock quantity per one package, unique per product/unit |

Only mass or count units may be product base units. Unit dimensions and
canonical factors cannot be changed after creation; create a new unit instead.
Cross-dimension conversions (pieces to kilograms, for example) are rejected.
A crate conversion must be explicitly defined for each product; there is no
global crate weight. Fixed packaging is for standardized packs only. Variable
delivery weights will be recorded as measured quantities by Inventory.
The calculator returns the product's base stock unit, uses decimal arithmetic
and rounds half up to six decimal places. It does not record a stock movement.
Updating a packaging definition changes future calculations; inventory
transactions must snapshot their applied conversion when that module is built.

Active definitions require active dependencies. A type, variety or unit
in use by active dependants cannot be deactivated until those dependants are
deactivated. Classifications referenced by products or received stock cannot
change kind/type. A grade can be deactivated to stop future receiving without
stranding stock already received; historical grade text remains unchanged.
Catalogue changes and their audit events commit together. No delete endpoints
are provided. The shared catalogue is business-wide; reading it does not grant
access to any branch's stock or sales.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/catalogue` | Types, classifications, units, products and fixed packaging definitions |
| POST | `/api/catalogue/types` | Create a produce type (`code`, `name`, `active`) |
| PUT | `/api/catalogue/types/{id}` | Update a produce type |
| POST / PUT | `/api/catalogue/classifications` / `/{id}` | Code, name, active, `produceTypeId`, `kind` (`VARIETY` or `GRADE`) |
| POST / PUT | `/api/catalogue/units` / `/{id}` | Code, name, active, `dimension` (`MASS`, `COUNT`, `PACKAGING`), `baseFactor` (null for packaging) |
| POST / PUT | `/api/catalogue/products` / `/{id}` | Code, name, active, `produceTypeId`, optional `varietyId`, `baseUnitId` |
| PUT | `/api/catalogue/packaging` | Upsert `productId`, `unitId`, `quantityInBaseUnit` |
| POST | `/api/catalogue/convert` | Calculate `quantity` from `fromUnitId` for `productId`; requires view permission and CSRF |

All mutations require CSRF. Catalogue writes require business-wide
`CATALOGUE_MANAGE`; reading/calculating requires `CATALOGUE_VIEW`.
Quantities/factors accept up to 13 integer digits and six fractional digits.
Decimal factors and conversion results are serialized as strings to avoid
losing precision when browser code loads and edits them.

Catalogue responses include a `version`. Updates to types, classifications,
units, products and existing packaging definitions require `If-Match` with
that quoted version (for example, `"0"`). Update responses also return an
`ETag`. Missing preconditions return 428; stale versions return 412.
New packaging definitions do not require an existing version. The catalogue
edit forms load and submit the version automatically.

### Workspace routes

`/home` is the date-filtered dashboard; `/ui/configurations` links to catalogue
and branches; `/ui/catalogue` provides maintenance and conversion;
`/ui/branches` and `/ui/users` are scoped read-only directories;
`/ui/reports/audit` is the date-filtered administrative audit report
(`/ui/reports` remains an alias). Direct page/API access is
authorized server-side, not just hidden in navigation.

## Phase 3: Inventory and spoilage

Expand **Stock** and select **Receive stock** (`/ui/stock-entries`) to post
incoming stock and inspect receipt batches and their costs. Select
**Stock Movement** (`/ui/stock`) to record spoilage, post corrections, reverse
eligible posted movements and maintain minimums. Both screens show live
on-hand quantities; inter-branch transfers are not implemented.

All read-only reports are grouped under **Reports**:
**Stock levels** (`/ui/reports/stock`) shows current/as-of branch/product
balances, receipt/spoilage interval comparisons, batch costs and expiry alerts.
**Stock movements** (`/ui/reports/stock-movements`) provides date/branch-filtered,
paginated movement history without posting or reversal controls.
Both require `STOCK_VIEW` and enforce the same branch scope as inventory.
Batch details are searched by lot/product/grade and paginated separately, with at most
100 batches per page. Stock Movement's **Find a batch** controls also determine
the batches available in its spoilage/correction pickers.
**Audit activity** (`/ui/reports/audit`) separately requires `AUDIT_VIEW` and
business-wide scope. Stock-only staff can open stock reports without audit access;
audit-only staff cannot open stock reports. Reports never contain inventory
mutation forms, even for administrators.

### Posting and traceability

- A receipt posts immediately, has a destination branch, physical received
  date, source/delivery reference, optional notes and 1–50 product lines.
  Every line creates its own batch with a lot reference and optional
  expiry/best-before date. Reusing a lot label does not merge separate deliveries.
- Grades are selected per receiving line, not when creating a product.
  Define one product such as **Local red onions**, and grades such as **Small**,
  **Medium** and **Large** under its produce type. Split a delivery into three
  lines with that same product and lot reference, choosing each grade with its
  own delivered quantity, unit and purchase cost. The optional receipt-line
  `gradeId` must identify an active `GRADE` of the product's produce type;
  omission means **Ungraded**, with no implicit product default.
  One receipt ID groups the delivery; each line has a distinct stock batch.
  Stock batch lists, searches, movement history and correction/spoilage pickers
  show the immutable received grade. Transfers retain it across branches even
  after the catalogue grade is renamed or deactivated.
  Product balances, low-stock thresholds, sales and FEFO selection remain
  product-level across grades; this change does not introduce grade-specific
  sales pricing or grade-specific allocation.
- V14 moves legacy product grade associations to stock where the saved grade
  name still matches that association, then removes the product grade column.
  Existing stock and transfer grade snapshots remain untouched; unmatched
  historical names are retained without guessing a classification identity.
  Existing product definitions are not merged or renamed automatically.
  V13 adds WhatsApp reporting; Flyway applies V13 before V14 when upgrading
  from V12, or applies V14 alone to an existing V13 deployment.
- Select a mass/count unit compatible with the product, a defined fixed
  packaging conversion, or enter the **actual total measured base quantity**
  for variable-weight packaging. That last value is the total for the line,
  not the weight per package. It is only allowed for packaging units.
- Input quantities, applied conversion numerator/denominator and resulting
  base quantities are saved on each batch. Later catalogue edits do not
  recalculate existing stock. A positive input that rounds to zero, a converted
  quantity outside the supported range, or an invalid line rejects the entire
  receipt, including its audit entry.
- Receipts require active branches/products/catalogue dependencies. Corrections
  and spoilage can dispose of stock whose product has since been deactivated.
  Business-wide staff can correct stock in closed branches; branch-scoped
  access to a deactivated branch is removed as in Phase 1.
- Spoilage takes a positive loss quantity and mandatory reason. Adjustments
  take a **signed delta** in the product's base unit, not a replacement balance.
  They also require a reason. Zero adjustments are rejected.
- Posted movements have no edit/delete endpoint. Reverse an original movement
  once to append the opposite quantity with an explanation. Receipt corrections
  reverse individual lines. Reversing a reversal is not permitted; a new,
  explicitly justified adjustment can correct a reversal.
- The ledger is authoritative. Each batch balance is a transactional projection
  of its movement sum. Postings lock the destination branch row before checking
  balances and retry IDs, preventing concurrent over-deductions, including
  receipt reversals. Movements, balances and audit events commit together.
  Negative stock and quantities exceeding the per-batch limit are rejected.

Every receipt, spoilage, adjustment or reversal requires a client-generated
UUID `requestId`. Retrying the same request as the same actor returns the
original operation without posting twice. Reusing it with a different payload
or actor returns 409. The browser retains this ID while a form is awaiting a
successful response, including network retries. After an uncertain result,
retry **without clearing or changing the form**; inspect movement history
before deliberately starting a new posting. Re-entering a delivery with a new
ID is a new business operation, not an automatic duplicate-reference check.

### Receipt purchase costs

New receipts require `currency` and a `purchaseUnitCost` on every line.
The business currency defaults to **Kenya Shilling (KES)** through
`platform.inventory.currency=${INVENTORY_CURRENCY:KES}`. The receiving screen
prefills it as a read-only field, and the server rejects another currency.
Invalid currency configuration fails startup rather than assuming a currency.
The currency is saved on each batch so configuration changes cannot reinterpret
previous receipt amounts. No currency conversion is performed.

Enter cost **per selected delivered unit**, not per base stock unit unless
they are the same. For example, two crates at KES 2,000 each cost KES 4,000.00.
If their actual total weight is 50 kg, the derived purchase cost is KES 80/kg.
For a fixed conversion, the same calculation uses the snapshotted received
base quantity. Different delivery lines remain separate batches and costs.

Unit costs are required, nonnegative decimals with up to 13 integer and six
fractional digits. Explicit zero is permitted for free stock; missing cost is
not treated as zero. The server multiplies delivered quantity by unit cost
using decimal arithmetic and rounds each original batch total **half up** to
the currency's standard fractional digits (two for KES). Rounded amounts and
the entered unit cost are saved immutably with the receipt. Retry identity
includes the submitted currency and costs.

The batch API and screens expose `currency`, `purchaseUnitCost`,
`receivedCost` (original rounded batch total) and `baseUnitCost` (that total
divided by original received base quantity, rounded half up to 12 decimal
places). Decimal amounts are returned as strings. The original total is
authoritative; the derived unit rate is informational and can have rounding
differences. Do not reconstruct a financial total by multiplying a rounded
derived rate.

These amounts are **original purchase costs, not current stock valuation**.
Spoilage, adjustments and reversals change quantities but do not overwrite the
original receipt cost. Phase 4 adds a separate remaining carrying value and
snapshotted cost-of-sales; the original receipt total remains immutable.
No landed-cost allocation or tax calculation is introduced here.
Amounts entered should represent the purchase price for that selected unit;
separately allocated freight/taxes are not added by the application.

V4 leaves earlier batches with null cost/currency fields. They are labelled
**Cost unknown**, not KES 0.00, and remain usable for inventory operations.
No cost backfill/edit API is provided. New API clients must send the costing
fields; an old receipt payload without them returns 400.

### Stock reporting and alerts

Stock screens support branch filters and the shared UTC date interval.
Balances are shown separately for each branch/product/base unit; kilograms,
pieces and differently configured product base units are never added together.
The current balance and batch/expiry alerts are **live snapshots**, independent
of the historical date selector. The as-of balance is reconstructed from
movements posted before midnight following the selected end date.

Receipt and spoilage columns show **net posted quantities** in the selected
interval and in the preceding equal-length interval. Reversals retain the
original receipt/spoilage category with the opposite sign, so a period may
legitimately have negative net receipts or net spoilage. Physical received
dates do not backdate the ledger or alter previous reports. Movement history
shows the posting time, actor ID, batch, source/reason and reversal reference,
with 100 rows per page and an uncapped matching count.
Current balances are aggregated in the database without loading every batch.
Inventory snapshots exclude batch detail rows; use the separate batch endpoint
for paginated batch quantities, receipt costs and conversion snapshots.

Minimum stock is optional and specific to a branch/product in its base unit.
Stock **at or below** the configured minimum triggers an alert, including a
configured product with no receipts. Zero is a valid minimum. Creating a
threshold uses `expectedVersion: -1`; updates must send its current version
from the balance row. Stale updates return 412; the UI's **Set minimum**
button loads the current version.

Available batches with expiry/best-before on or before seven days from today
are flagged, including already-expired batches. Expiry does not silently deduct
stock: an authorized user records the actual spoilage.

### Inventory API

All mutations require the existing CSRF header and local branch scope.
Posting permissions are independent of `STOCK_VIEW`; the view permission
includes inventory product/unit/branch pickers, not catalogue maintenance.
Quantities and purchase unit costs accept up to 13 integer and six fractional
digits; responses retain decimal strings.

| Method | Endpoint | Permission / purpose |
| --- | --- | --- |
| GET | `/api/inventory?branchId=...&start=YYYY-MM-DD&end=YYYY-MM-DD&page=0` | `STOCK_VIEW`; optional branch and dates, zero-based movement page |
| GET | `/api/inventory/batches?branchId=...&search=...&page=0` | `STOCK_VIEW`; scoped batch details and receipt costs, lot/product search, at most 100 rows per page |
| POST | `/api/inventory/receipts` | `STOCK_RECEIVE`; create batches and movements; 201 |
| POST | `/api/inventory/spoilage` | `STOCK_SPOILAGE`; positive loss quantity |
| POST | `/api/inventory/adjustments` | `STOCK_ADJUST`; signed quantity delta |
| POST | `/api/inventory/reversals` | `STOCK_ADJUST`; reverse one original movement |
| PUT | `/api/inventory/thresholds` | `STOCK_ADJUST`; version-checked minimum |

Example receipt (replace placeholder IDs, including a fresh UUID for `requestId`):

```json
{
  "requestId": "fresh-request-uuid",
  "branchId": "branch-uuid",
  "receivedDate": "2026-10-24",
  "source": "Farm / delivery reference",
  "currency": "KES",
  "notes": "Measured at receiving",
  "lines": [{
    "productId": "product-uuid",
    "unitId": "crate-unit-uuid",
    "quantity": "3",
    "purchaseUnitCost": "2000.00",
    "measuredBaseQuantity": "57.125",
    "lotCode": "FARM-LOT-001",
    "expiryDate": "2026-10-29"
  }]
}
```

For ordinary/fixed-unit receipts, omit `measuredBaseQuantity`.
Spoilage/adjustment bodies use `requestId`, `branchId`, `batchId`, `quantity`
and `reason`. Reversals use `requestId`, `branchId`, `movementId` and `reason`.
Threshold bodies use `branchId`, `productId`, `minimumQuantity` and
`expectedVersion`.

No sale deduction, inter-branch transfer workflow, selling-price management,
remaining-stock valuation,
supplier balances, customer receivables or automatic replenishment is
implemented in Phase 3.

## Phase 4: Sales and profit reports

### Checkout and branch assignment

Open **Sales > New sale** (`/ui/sales`). A salesperson must have `SALE_CREATE`,
`allBranches=false` and **exactly one assigned active branch**. The server
derives the branch from the approved staff record; no branch selector is
rendered or trusted in a sale request. Business-wide/multi-branch managers can
report within scope but cannot sell until assigned a single selling branch.
Changing staff scope continues to revoke existing sessions as in Phase 1.

**Sale date defaults to the present day in UTC.** The date picker accepts today
or a past date (1970-01-01 onward) so a completed sale can be recorded after
downtime. Future dates are rejected by the server. Both quote and sale APIs
accept optional `saleDate`; omission preserves the today default for existing
clients. Changing the date invalidates the quote and payment confirmation.

The selected date is immutable and shown separately from the actual **Recorded
at** timestamp on the receipt. Backdated sales have an additional `SALE_BACKDATED`
audit event. Sales, payment summaries, gross-profit and net-profit reports use
the selected sale date; refunds keep their own actual posting date. V12 backfills
existing sales from their UTC posting dates without changing their timestamps.
Back up PostgreSQL before applying this migration.

Backdating does **not** recreate an earlier stock ledger. Stock is deducted now
from currently available quantities and carrying costs, limited to batches
received by the selected date and unexpired on that date. Transferred batches
cannot be sold before their recorded arrival date. A batch that expired since
the actual sale can be allocated for that past date, but not for today's sale.
Reconcile physical stock and record any missing receipts before recovery entry;
use notes to describe the outage. Historical transfer arrivals and unavailable
stock are not invented, and no negative stock is permitted.

The customer dropdown defaults to **Walk-ins**. It suggests up to 500 distinct
names previously used at that branch; **Add customer...** accepts a new name
or one not in the suggestions. The selected name is saved on the receipt and
appears in the customer report. These are transaction customer names, not a
customer-credit/CRM register or globally exposed customer directory.

Enter one or more products, selling units, quantities and unit selling prices.
Fixed packaging and actual measured packaging use the same conversion service
as receipts; the sale snapshots its conversion and product/unit labels.
**Calculate total** validates the combined quantities across all lines against
stock eligible on the selected sale date and computes the rounded payable amount
on the server, without posting stock or reserving it. Recalculate after changing lines or date, receive the
full payment, confirm it and post. A concurrent checkout can change availability;
posting rechecks everything atomically.

Cash, manually recorded M-Pesa and bank payments are supported. Noncash
payments require a reference, normalized to uppercase and unique per payment
method across the business, including voided sales. One reference cannot fund
multiple receipts. No money is collected or refunded by this application.
Credit, partial payments, split tenders, payment gateways, partial returns,
maintained selling-price lists, discounts as separate adjustments, expenses (added in Phase 6),
VAT calculation and eTIMS integration are outside this phase. Enter final
unit prices; receipts explicitly state that they are **not tax invoices**.

### Allocation, costing and corrections

Sales allocate automatically by earliest expiry, then receipt date, creation
time and batch ID; undated stock is last. The expiry date is the last sellable
day in UTC. Stock expired on the selected sale date is never allocated.
Allocation continues beyond the first 100 batches. Insufficient stock, an
allocated unknown-cost batch or a different-currency batch rejects the entire
sale. Unknown legacy costs are not assumed to be zero or silently bypassed.
There is no batch selector or automatic negative-stock posting.

V5 initializes known batches' **remaining carrying value** from original
receipt cost and remaining/original base quantities, rounded directly in the
batch currency; unknown costs remain null. This is an opening valuation,
not reconstructed historical COGS. Subsequent sales allocate the current
batch carrying value proportionally with half-up currency rounding. Residual
cents remain with stock and a full depletion consumes its exact remaining
value. Spoilage/corrections also update carrying value and snapshot signed
cost changes. Positive corrections use the original receipt cost basis;
they are audited inventory gains, not new purchases.

Sale lines and batch allocations snapshot their amounts. A branch-row lock
serializes sales with receiving, corrections, spoilage and voids. Stock,
carrying values, allocations, payment record, sale and audit commit together.
UUID `requestId` values use the same actor/payload-sensitive idempotency
mechanism as inventory; retries do not post twice. After an uncertain result,
retry unchanged without refreshing/clearing the form or issuing a new ID.

Each posted sale has a printable `PR-<UUID>` receipt. The original branch,
salesperson/customer names, quantities, applied conversions, price and batch
allocations are retained. Sales responses hide cost/profit fields without
`PROFIT_VIEW`; original inventory receipt costs continue to be visible through
`STOCK_VIEW` as in Phase 3. Do not grant `STOCK_VIEW` where purchase costs
must be confidential.

`SALE_VOID` also permits inspecting an in-scope receipt to perform/review its
correction, but does not grant cross-staff summary reports.
It allows a **whole-sale void only** after all goods were physically
returned and a full refund manually completed. Noncash refunds require a refund
reference. A void restores the original batches and exact allocated costs,
retains the original receipt and records the reason, actor and refund.
Retries are safe and a second independent void is rejected. Sales movements
cannot be reversed through the generic inventory reversal API.
Reversals that would produce negative carrying value or a value without stock
are rejected for review rather than silently discarding cost.

### Reports and dashboard

All reports are under **Reports**, never under operational Stock/Sales menus.
Sales-only staff see their own sales and performance. `SALES_VIEW` grants
branch-scoped cross-staff reporting; it does not grant costs. Financial
reports require `PROFIT_VIEW`; stock losses require `STOCK_VIEW` and hide costs
unless `PROFIT_VIEW` is also present. There is no role-name shortcut.

| Report path under `/ui/reports/` | Contents |
| --- | --- |
| `sales` | Posted sales/refund ledger, receipt drill-down and total counts |
| `daily-sales` | Daily UTC net revenue, counts and refunds |
| `product-sales` | Base-unit quantities, revenue and optional cost/margin by product |
| `branch-sales` | Revenue, receipt/refund counts and optional cost/margin by branch |
| `staff-performance` | Attributed sales/refunds, revenue and optional cost/margin per salesperson and branch |
| `customer-sales` | Revenue and receipt/refund counts by selected customer, including Walk-ins |
| `payments` | Cash/M-Pesa/bank net receipts and manually recorded full refunds |
| `profit` | Net revenue, COGS and gross profit by branch and currency |
| `stock-valuation` | Current physical-stock carrying values, including expired stock, with unknown-cost entry counts |
| `stock-losses` | Period spoilage less reversals, quantity and authorized known costs; legacy known losses use prorated receipt costs |
| `stock`, `stock-movements`, `audit` | Existing stock balances/alerts, immutable movements and administrative audit |

Date-filtered reports use inclusive UTC dates and an exclusive next-day
boundary, default to this week and accept up to 366 days. Sales are recognized
on their selected sale date; full refunds/COGS reversals are negative events on the
void posting date. Voiding does not rewrite an earlier interval. Amounts are
never combined across currencies. Product quantities remain in their own base
units. Current stock valuation is explicitly **not historical** and has no
date inputs. Its known-cost totals exclude unknown values, not substitute zero.
SQL aggregation covers every matching transaction; detail/group rows are
bounded to 100 per page and totals are not truncated with the page.

**Gross profit = net sales revenue minus net allocated COGS.** It is not net
business profit and does not subtract operating expenses, taxes or separately
reported stock losses. Dashboard trend cards show absolute changes from the
previous equal-length interval, including a zero-sales current interval;
staff performance never counts sign-ins as sales.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/sales/entry` | Assigned selling branch, available catalogue choices and branch customer suggestions |
| POST | `/api/sales/quote` | Validate `lines` and calculate total without reserving/posting stock |
| POST | `/api/sales` | Post a paid sale, atomic stock/cost deductions and receipt |
| GET | `/api/sales/{id}` | Authorized receipt and snapshotted batch allocations |
| POST | `/api/sales/{id}/void` | Confirmed full refund/return and exact stock/cost restoration |
| GET | `/api/sales/reports/{report-path}` | Same scoped reports as the UI; optional `branchId`, `start`, `end`, `page` |

```json
{
  "requestId": "client-generated-uuid",
  "customer": "Walk-ins",
  "paymentMethod": "CASH",
  "lines": [{
    "productId": "product-uuid",
    "unitId": "kg-unit-uuid",
    "quantity": "75",
    "unitPrice": "120"
  }]
}
```

Omitting `customer` defaults to Walk-ins. Noncash sales also supply
`paymentReference`. A void supplies a new `requestId`, mandatory `reason`,
`returnedAndRefunded=true` and, for noncash, `refundReference`.
Monetary amounts and quantities are decimal strings, never binary floats.

### Version 1.0 deployment checks

Before using this phase live:

1. Back up PostgreSQL and verify restoration. Rehearse V4-to-V6 on a restored
   staging copy; check opening quantity/carrying-value totals and explicitly
   resolve unknown-cost inventory before selling it. Do not reset production
   data, edit applied migrations or invent costs to bypass the sales guard.
2. Add the four new client permission roles above in Keycloak, include them
   in the intended job-function composites/`app_roles` mapper and sign in
   again. A salesperson needs `SALE_CREATE` and one assigned branch. A branch
   supervisor typically needs `SALES_VIEW` and `SALE_VOID`; finance reporting
   additionally needs `PROFIT_VIEW`. Grant global roles/scope only deliberately.
3. Deploy/restart the new application artifact and keep credentials outside
   source control. Use HTTPS, secure cookies, restricted database/network
   access and production Keycloak configuration. Preserve tested backup,
   monitoring, recovery and release rollback procedures.
4. Reconcile opening stock, a real branch's sales/receipts, manually recorded
   payments/refunds, stock losses, carrying values and COGS with the business.
   Confirm VAT/eTIMS obligations separately; this is not a tax-invoicing system.
5. Perform acceptance testing with actual Keycloak roles and representative
   staff/branches on staging. Automated database/browser results are not a
   blanket sign-off of production infrastructure or tax compliance. Set/tag
   the final release version only after accepting that rollout.

Branch creation/update uses:

```json
{"code":"MAIN","name":"Main Branch","address":"Market Road","active":true}
```

Staff approval uses the Keycloak user ID as `subject`; the server supplies its
configured issuer. Neither roles nor passwords are accepted:

```json
{"username":"sales.one","fullName":"Salesperson One","subject":"keycloak-user-id","allBranches":false,"branchIds":["branch-uuid"]}
```

Staff update uses `fullName`, `active`, `allBranches` and `branchIds`; the
username and identity cannot be rebound. Global staff may have an empty branch
list. Self-deactivation and removing your own global scope are rejected;
another authorized administrator must perform those actions. Because
Keycloak owns role assignments, the application cannot guarantee that a last
Keycloak administrator remains available. Maintain a recovery administrator
and manage IdP roles deliberately.

From an authenticated browser on the application origin, fetch a fresh CSRF
token after login and supply its named header for every mutation:

```javascript
const csrf = await fetch('/api/auth/csrf').then(r => r.json());
const response = await fetch('/api/branches', {
  method: 'POST',
  headers: {'Content-Type': 'application/json', [csrf.headerName]: csrf.token},
  body: JSON.stringify({code: 'MAIN', name: 'Main Branch', address: '', active: true})
});
if (!response.ok) throw new Error(await response.text());
console.log(await response.json());
```

For logout, submit a POST form to `/api/auth/logout` with a hidden field named
by `csrf.parameterName` and containing `csrf.token`. Browser navigation follows
the provider logout redirect; an AJAX request is not a replacement for that
navigation. The existing exact post-logout URI `http://localhost:8080/` remains
valid: after Keycloak has ended the SSO session, its redirect to `/` starts
a fresh Authorization Code + PKCE sign-in with the branded Keycloak screen.

Errors use `application/problem+json`: 400 invalid input, 401 absent/expired/
revoked authentication, 403 insufficient access or invalid/missing CSRF,
404 absent resource, 409 duplicate/conflicting/concurrent edits, 412 stale
update precondition and 428 missing update precondition. Branch and staff
responses include a `version`; individual branch responses and create/update
responses also include its quoted version as an `ETag`. Send that value in
`If-Match` on updates. Staff versions are available in the `/api/users` list.
Errors never include credentials, tokens or rejected secret values.

Local staff updates revoke existing sessions on their next request.
Permissions are a snapshot of the ID token: Keycloak role changes, account
disablement and remote logout are **not immediately propagated**. Require
fresh OIDC login after at most five minutes, or ID-token expiry if earlier.
An existing Keycloak SSO session can make this reauthentication seamless.
Local sessions also expire after 30 minutes of inactivity and do not survive
application restarts. Cookies are HttpOnly and SameSite=Lax so the top-level
GET OIDC callback works. Use HTTPS and `SESSION_COOKIE_SECURE=true` in deployed
environments. Back-channel logout, clustered sessions and bearer-token APIs
are outside this phase.

## Database migrations and safety

The initial migration,
`src\main\resources\db\migration\V1__branches_and_access.sql`, containing the
complete Phase 1 schema:

| Table | Purpose |
| --- | --- |
| `branches` | Unique normalized code, name, address, activation and optimistic version |
| `staff_users` | Unique local username and issuer/subject identity, approval, branch scope and session-security version; no password or role |
| `user_branches` | Staff/branch assignments with foreign keys and a composite primary key |
| `audit_events` | Actor, action, target and time; no credentials or tokens |

`V2__catalogue_and_units.sql` adds `produce_types`, `product_classifications`,
`units_of_measure`, `products` and `product_packaging` with foreign keys,
uniqueness and positive-factor constraints. It seeds KG, G, PIECE, CRATE and
BAG, but no sample produce/products or business transactions.
V1 remains unchanged; V2 upgrades the deployed Phase 1 schema without replacing
staff identities, branches or audit history.

`V3__inventory_and_spoilage.sql` adds `stock_operations`, `stock_batches`,
`stock_movements` and `stock_thresholds`, with foreign keys, quantity checks
and unique reversal/threshold constraints. It preserves V1/V2 and all existing
access/catalogue data. It creates no sample stock, receipts or thresholds.

`V4__receipt_purchase_costs.sql` adds nullable currency, purchase unit cost and
original received cost fields to batches, with all-or-none/nonnegative cost
constraints. Earlier batches preserve their quantities and unknown costs.
New receipts require the costing fields at the API boundary. V1–V3 remain
unchanged.

`V5__sales_and_profit.sql` adds immutable sale/line/allocation data and a
sales/refund event view, extends the stock ledger for sales/voids and adds
remaining carrying values and signed movement cost changes. It preserves
existing transactions and unknown receipt costs, initializes only opening
known-cost valuations and creates no invented sales or historical profit.
V1-V4 remain unchanged.

`V6__staff_access_requests.sql` adds a uniquely issuer/subject-bound pending
access-request table. It changes no existing approvals, branch assignments,
stock or sales. New staff still require explicit administrator approval.

The uncommitted Phase 1 migrations were consolidated before deployment, after
confirming the local PostgreSQL database had no applied migrations or business
tables. This V1 is for a fresh schema; it is **not** an upgrade from an earlier
experimental local-role schema. If another database has already applied an
earlier V1/V2, stop and arrange an explicit data-preserving upgrade rather than
repairing checksums, deleting history or resetting data.

Flyway owns schema creation; Hibernate only validates it. Flyway clean is
disabled. Once this initial migration is deployed, preserve it and use new
versioned migrations for future changes. Administrative mutations and their
audit events commit in the same transaction. Database uniqueness and foreign
keys protect integrity; optimistic locking detects conflicting edits.

No physical-delete API is exposed; deactivate branches/staff. Audit has no
edit/delete API. Database-enforced append-only access and full before/after
audit snapshots are not implemented.

## Tests and disposable H2 mode

```powershell
.\mvnw.cmd clean verify
```

Tests activate the test-only datasource profile, start a real application HTTP
server and a local OIDC provider with RSA-signed ID tokens, and exercise actual
authorization redirects, code exchange, client authentication, PKCE, state,
nonce, signature, issuer/audience and token expiry. They also cover approved
identities, permission mapping, branch isolation, CSRF, staff revocation,
logout, validation, bootstrap safeguards, foreign keys and audit persistence.
Onboarding coverage includes pending login without business access,
concurrent request deduplication, privileged/CSRF-protected approval, immutable
verified identity, failed-approval rollback, invalid claims/tokens, inactive
staff and manual pre-approval compatibility.
Phase 2 tests cover migration preservation, catalogue permissions, matching
classifications, dependency deactivation, dimension safety, explicit packaging
factors, catalogue templates and date-filtered dashboard/report boundaries.
Phase 3 covers V2-to-V3 preservation, atomic multi-line receipts, saved
conversion factors, measured packaging, duplicate retries, competing
deductions, reversals, branch isolation, CSRF, versioned thresholds, expiry
alerts and posting-date/as-of reporting. Receipt costing tests cover selected
receiving units, measured packaging, KES rounding, zero/invalid/missing costs,
retry identity, unchanged original amounts after stock losses, and V3-to-V4
preservation of unknown legacy costs.
Phase 4 adds FEFO and bounded multi-batch allocation, unit/packaging snapshots,
atomic insufficient-stock failures, branch-derived selling, own/manager
report scope, customer defaults, references, full voids, cent-conserving
carrying values, concurrent oversell/void prevention, uncapped report totals,
UTC posting/refund boundaries and V4-to-V5 opening-cost preservation.
Phase 5 adds integrated transfer dispatch/arrival/loss, FEFO/rollback, scoped
permissions and hidden costs, full-loss/unknown-cost handling, retry and
concurrent dispatch/receive protection, transfer-versus-sale oversell prevention,
generic reversal denial, ledger conservation and V7-to-V8 migration checks.
Run the focused transfer and regression checks with:

```powershell
.\mvnw.cmd "-Dtest=TransferIntegrationTests,InventoryMigrationTests,InventoryIntegrationTests,SalesIntegrationTests,AccessIntegrationTests" test
```
No live PostgreSQL/Keycloak instance or real credentials are needed.

An opt-in headless Chrome/Chromium browser test exercises consecutive
create/edit saves, table refreshes, exact packaging calculations, multi-line
inventory receiving with unit/batch costs, spoilage, corrections, reversals, threshold editing,
visible validation errors and mobile overflow. It also exercises first login,
pending-request review/approval without copying user IDs, branch reassignment and paid checkout,
Walk-ins/new customer selection,
manual M-Pesa recording, a manager's full refund, sales/performance/profit
reports and dashboard cards, transfer dispatch/arrival, missing-loss-reason
feedback, permanent allocation loss and mobile transfer history. It uses the local signed OIDC
fixture, an isolated browser profile and the browser's built-in debugging
protocol; no Selenium driver or browser download is required.
Isolated browser profiles are created under `target/browser-profiles` (removed
by `clean`), not your normal Chrome profile.
Point `ui.browser` to your installed compatible Chromium executable:

```powershell
.\mvnw.cmd "-Dtest=CatalogueBrowserTests" "-Dui.browser=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" test
```

The browser test is skipped when that property is absent, keeping the normal
test suite usable on machines without a browser.

To repeat the access/onboarding and sales integration cases and the entire Chrome workflow on
**PostgreSQL**, create a disposable, password-protected database named exactly
`primeroot_phase4_test` on a loopback PostgreSQL server. Set `PG_TEST_URL`
(`jdbc:postgresql://127.0.0.1:<port>/primeroot_phase4_test`),
`PG_TEST_USERNAME` and `PG_TEST_PASSWORD` to disposable test credentials, then run:

```powershell
.\mvnw.cmd "-Dtest=PostgresAccessIntegrationTests,PostgresSalesIntegrationTests,PostgresTransferIntegrationTests,PostgresBrowserTests" "-Dui.browser=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" verify
```

These checks use separate `access_e2e`, `sales_e2e`, `transfers_e2e` and `browser_e2e` schemas and **delete
their fixture data**. Never point them at a live database. The harness rejects
non-loopback URLs, other database names and URL parameters before database
initialization. PostgreSQL checks are skipped unless `PG_TEST_URL` is set;
the Chrome check additionally requires `ui.browser`. No Docker or new test
dependency is required. Running `clean verify` with these variables and the
browser property includes both H2 and PostgreSQL checks. PostgreSQL 18.6 was
used for Phase 4's isolated database and browser verification.

A second opt-in browser check verifies the actual Keycloak theme, including
native username/password controls, invalid-credential feedback, password
recovery, authenticator setup, local artwork and mobile layout. Use an isolated
Keycloak realm named `prime-root-theme-test` with `loginTheme=prime-root`,
password reset enabled and a `theme-fixture` user whose required actions include
`CONFIGURE_TOTP`. Do not use a production realm or real staff credentials.
Set `KEYCLOAK_THEME_TEST_URL` to that local realm's valid OIDC authorization URL
(including the test client's redirect URI and PKCE parameters), and
`KEYCLOAK_THEME_TEST_PASSWORD` to the fixture account's password. Then run:

```powershell
.\mvnw.cmd "-Dtest=CatalogueBrowserTests#keycloakThemeRetainsNativeCredentialsErrorsRecoveryAndAuthenticatorSetup" "-Dui.browser=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" test
```

The theme check requires a loopback host and the exact isolated realm name.
It is skipped unless `KEYCLOAK_THEME_TEST_URL` is set; ordinary application
tests continue using the local OIDC fixture, without a Keycloak installation.

For a read-only appearance check against a running local Keycloak login page,
set `KEYCLOAK_THEME_VISUAL_TEST_URL` to its valid OIDC authorization URL, then run:

```powershell
.\mvnw.cmd "-Dtest=CatalogueBrowserTests#keycloakBrandIsReadableAndNetworkTaglineAlignsWithLoginFields" "-Dui.browser=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" test
```

This check requires a loopback host and never submits credentials. It verifies
the fresh-produce headline, brand text contrast, tagline alignment with the
login fields at desktop widths, red bulb onion artwork description and mobile
overflow.

H2 runs in PostgreSQL compatibility mode; it is not a replacement for checking
actual PostgreSQL startup and migrations. H2, test configuration and the OIDC
fixture are excluded from the executable application.

To run manually on disposable H2 **with real Keycloak**, configure the Keycloak
environment variables and explicit bootstrap identity above, then:

```powershell
.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=test"
```

The automated OIDC fixture is not started by this command. H2 data disappears
when the process exits, so bootstrap is needed for every new manual H2 process.
No PostgreSQL credentials are required in this mode.

Next modules: full accounting, farm tracking and AI. Expense recording and
operating net-profit reporting are implemented in Phase 6; a general ledger
and statutory accounting remain separate future work. Sales/payments and
inter-branch transfers already use the shared branch inventory ledger.
