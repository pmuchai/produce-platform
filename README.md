# Prime Root

A modular fresh-produce platform for branch operations, inventory, sales and
spoilage control. Phase 0 provides the backend foundation; Phase 1 provides
Branches and Access. Phase 2 adds Catalogue and Units, maintenance screens and
a shared, permission-aware workspace. Phase 3 adds branch inventory, receipts,
batch traceability, receipt purchase costs, spoilage, corrections and stock reporting.

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

Permissions are independent: managing does not implicitly grant viewing.
Only these exact permission codes in the validated ID token's `app_roles`
array become authorities. A composite name such as `ADMIN` alone grants
nothing; its effective component permission roles must also appear in that
claim. Unknown roles are ignored, while missing or malformed `app_roles`
rejects login. An approved identity with an empty array may view only its own
profile and the permission catalogue.

Local staff records explicitly approve an immutable `(issuer, subject)` pair,
and retain a local username, display name, active flag, branch assignments and
`allBranches` scope. The Keycloak user ID is the token's subject. No identity
is automatically created or matched by email/username. `allBranches` grants
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
4. The eleven client permission roles listed above. Create a composite `ADMIN`
   role containing all eleven. Create other job-function composites from the
   relevant permissions and assign them to staff.
5. A **User Client Role** protocol mapper for client `produce-platform`, claim
   `app_roles`, JSON type `String`, multivalued, included in the **ID token**.
   Including it in access tokens is optional for this session-based backend.
   Ensure client role scope mappings allow the effective permission roles.
   If Full Scope Allowed is disabled, explicitly map the required roles.
6. A test staff user with a permanent Keycloak credential and the composite
   `ADMIN` assignment. Copy that user's **ID**, not a role ID or client ID.
   Use Keycloak's token evaluation to check that `app_roles` includes the eleven
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

Browser redirects need no permissive CORS configuration. No application CORS
policy or wildcard Web Origins is needed for this same-origin backend.
Keep the client secret private. Local HTTP is development-only; deployed
Keycloak and the application must use HTTPS and exact production URLs.

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
configuration. Open `http://localhost:8080/` in a browser to see the green
Prime Root sign-in page and its locally bundled produce-distribution artwork.
Select **Sign in securely**, sign in at Keycloak, and verify that `/home` displays your welcome
dashboard. `/api/auth/me` still exposes your local staff profile and the expected
effective permission codes. No password is
submitted to this application.

Remove secrets from the terminal environment when finished:

```powershell
Remove-Item Env:DB_PASSWORD
Remove-Item Env:KEYCLOAK_CLIENT_SECRET
Remove-Variable dbCredential, clientCredential
```

## API and session workflow

Spring Security performs OIDC discovery, code exchange, PKCE S256, signature,
issuer/audience, nonce and authorization-state validation. The browser retains
only a server session cookie; APIs do not accept local credentials or arbitrary
bearer tokens. API authentication failures return JSON instead of redirecting
to the identity provider.

| Method | Endpoint | Result |
| --- | --- | --- |
| GET | `/`, `/login` | Public Prime Root sign-in page; authenticated staff redirect to `/home` |
| GET | `/oauth2/authorization/keycloak` | Initiate browser login |
| GET | `/login/oauth2/code/keycloak` | Provider callback; redirects to `/home` after approval |
| GET | `/home` | Welcome dashboard with permission- and scope-filtered summaries |
| GET | `/api/auth/csrf` | Public CSRF token, header name and parameter name |
| GET | `/api/auth/me` | Current local `staff` profile and effective `permissions` |
| POST | `/api/auth/logout` | CSRF-protected local logout; redirects through Keycloak logout to the Prime Root sign-in page at `/` |
| GET | `/api/permissions` | Authenticated capability catalogue, not local role management |
| GET | `/api/branches` | Branches visible to the current staff member |
| GET | `/api/branches/{id}` | Branch within scope |
| POST | `/api/branches` | `BRANCH_MANAGE` and global scope; 201 with Location |
| PUT | `/api/branches/{id}` | `BRANCH_MANAGE` within scope; requires the current `If-Match` ETag |
| GET | `/api/users` | `USER_VIEW` and global scope |
| POST | `/api/users` | `USER_MANAGE` and global scope; 201 with Location |
| PUT | `/api/users/{id}` | `USER_MANAGE` and global scope; requires the current `If-Match` ETag |
| GET | `/api/audit-events` | `AUDIT_VIEW` and global scope; latest 100 events |

Anonymous visits to `/home` or `/ui/**`, and expired workspace sessions, return
to `/login`. APIs continue to return 401 instead of a login-page redirect.
The public page, logo and original produce illustration are served locally,
without third-party image/font services. The sign-in button starts the existing
Keycloak Authorization Code + PKCE flow; the application never collects passwords.
The Keycloak-hosted credential screen remains controlled by Keycloak's realm
theme. UI branding is **Prime Root**; existing technical application, realm and
client identifiers remain unchanged.

The left sidebar uses expandable parent sections. Click a parent (or use
Enter/Space on its heading) to reveal its children. The current section opens
automatically and its active child is highlighted.

| Parent | Children |
| --- | --- |
| Dashboard | Overview |
| Sales | New sale (coming later) |
| Stock | Receive stock, Stock Movement |
| Reports | Stock levels, Stock movements, Audit activity |
| User management | Staff directory |
| Configurations | Overview, Catalogue and units, Branches |

Children without the required permissions are visibly unavailable, not
functional links. Future modules are also marked as unavailable. User
management currently lists approved staff; staff mutations remain available
through the Phase 1 APIs. Catalogue maintenance has working create/edit forms.

The dashboard defaults to Monday through today in **UTC**, with inclusive
start/end date controls (maximum 366 days, no future dates). Global `AUDIT_VIEW`
shows the actual number of audit events in that interval and the absolute
change against the preceding equal-length interval. Counts are not capped at
100; the report shows the most recent 100 matching events and the total count.
Sign-ins and administrative activity are explicitly not staff performance.
Sales trends, branch performance and user performance remain unavailable
until sales transactions exist. With `STOCK_VIEW`, real branch/product stock,
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
| Variety / grade | Configurable within one produce type; a product must reference the correct kind and matching type |
| Product | Unique normalized code, name, type, optional variety/grade and immutable base stock unit |
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

Active definitions require active dependencies. A type, variety, grade or unit
in use by active dependants cannot be deactivated until those dependants are
deactivated. Classifications referenced by products cannot change kind/type.
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
| POST / PUT | `/api/catalogue/products` / `/{id}` | Code, name, active, `produceTypeId`, optional `varietyId`/`gradeId`, `baseUnitId` |
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
Batch details are searched by lot/product and paginated separately, with at most
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
original receipt cost. No landed-cost allocation, tax calculation, remaining
stock valuation, cost-of-sales or profit calculation is introduced here.
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
valid: its redirect displays the Prime Root sign-in page after Keycloak has
ended the SSO session. Selecting **Sign in securely** starts a fresh
Authorization Code + PKCE sign-in.

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
No live PostgreSQL/Keycloak instance or real credentials are needed.

An opt-in headless Chrome/Chromium browser test exercises consecutive
create/edit saves, table refreshes, exact packaging calculations, multi-line
inventory receiving with unit/batch costs, spoilage, corrections, reversals, threshold editing,
visible validation errors and mobile overflow. It uses the same local signed OIDC
fixture, an isolated browser profile and the browser's built-in debugging
protocol; no Selenium driver or browser download is required. Point
`ui.browser` to your installed compatible Chromium executable:

```powershell
.\mvnw.cmd "-Dtest=CatalogueBrowserTests" "-Dui.browser=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" test
```

The browser test is skipped when that property is absent, keeping the normal
test suite usable on machines without a browser.

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

Next module: Sales and payments, using the branch inventory ledger for safe
stock deductions. Inter-branch transfers, accounting, farm tracking and AI
remain later modules.
