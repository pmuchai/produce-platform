# Produce Platform

A modular fresh-produce platform for branch operations, inventory, sales and
spoilage control. Phase 0 provides the backend foundation; Phase 1 provides
Branches and Access. Phase 2 adds Catalogue and Units, maintenance screens and
a shared, permission-aware workspace.

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
4. The seven client permission roles listed above. Create a composite `ADMIN`
   role containing all seven. Create other job-function composites from the
   relevant permissions and assign them to staff.
5. A **User Client Role** protocol mapper for client `produce-platform`, claim
   `app_roles`, JSON type `String`, multivalued, included in the **ID token**.
   Including it in access tokens is optional for this session-based backend.
   Ensure client role scope mappings allow the effective permission roles.
   If Full Scope Allowed is disabled, explicitly map the required roles.
6. A test staff user with a permanent Keycloak credential and the composite
   `ADMIN` assignment. Copy that user's **ID**, not a role ID or client ID.
   Use Keycloak's token evaluation to check that `app_roles` includes the seven
   effective permission codes.

**Upgrading from Phase 1:** add `CATALOGUE_VIEW` and `CATALOGUE_MANAGE` client
roles to the ADMIN composite and appropriate client role scope mappings.
Existing ADMIN assignments do not automatically acquire new capabilities just
because the application adds permission codes. Sign out and sign back in to
obtain the updated ID-token claims. Catalogue readers need only
`CATALOGUE_VIEW`; editing screens require that view permission plus
`CATALOGUE_MANAGE` and local business-wide scope.

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
configuration. Open `http://localhost:8080/oauth2/authorization/keycloak` in a
browser, sign in at Keycloak, and verify that `/home` displays your welcome
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
| GET | `/` | Redirects to Keycloak sign-in, including after provider logout |
| GET | `/oauth2/authorization/keycloak` | Initiate browser login |
| GET | `/login/oauth2/code/keycloak` | Provider callback; redirects to `/home` after approval |
| GET | `/home` | Welcome dashboard with permission- and scope-filtered summaries |
| GET | `/api/auth/csrf` | Public CSRF token, header name and parameter name |
| GET | `/api/auth/me` | Current local `staff` profile and effective `permissions` |
| POST | `/api/auth/logout` | CSRF-protected local logout; redirects through Keycloak logout to `/`, then Keycloak sign-in |
| GET | `/api/permissions` | Authenticated capability catalogue, not local role management |
| GET | `/api/branches` | Branches visible to the current staff member |
| GET | `/api/branches/{id}` | Branch within scope |
| POST | `/api/branches` | `BRANCH_MANAGE` and global scope; 201 with Location |
| PUT | `/api/branches/{id}` | `BRANCH_MANAGE` within scope |
| GET | `/api/users` | `USER_VIEW` and global scope |
| POST | `/api/users` | `USER_MANAGE` and global scope; 201 with Location |
| PUT | `/api/users/{id}` | `USER_MANAGE` and global scope |
| GET | `/api/audit-events` | `AUDIT_VIEW` and global scope; latest 100 events |

The left sidebar provides Dashboard, Sales, Receivables / Stock entries,
Stock movement, Reports, User management and Configurations. Future modules
are visibly unavailable rather than linking to nonfunctional pages.
Configurations links to the catalogue and authorized branch listings. User
management currently lists approved staff; staff mutations remain available
through the Phase 1 APIs. Catalogue maintenance has working create/edit forms.

The dashboard defaults to Monday through today in **UTC**, with inclusive
start/end date controls (maximum 366 days, no future dates). Global `AUDIT_VIEW`
shows the actual number of audit events in that interval and the absolute
change against the preceding equal-length interval. Counts are not capped at
100; the report shows the most recent 100 matching events and the total count.
Sign-ins and administrative activity are explicitly not staff performance.
Sales trends, stock balances, branch performance and user performance are
labelled as unavailable until the corresponding transaction modules exist;
no illustrative values or zero balances are presented as real business data.

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

### Workspace routes

`/home` is the date-filtered dashboard; `/ui/configurations` links to catalogue
and branches; `/ui/catalogue` provides maintenance and conversion;
`/ui/branches` and `/ui/users` are scoped read-only directories; `/ui/reports`
is the date-filtered administrative audit report. Direct page/API access is
authorized server-side, not just hidden in navigation.

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
valid: its redirect starts a fresh Authorization Code + PKCE sign-in after
Keycloak has ended the SSO session.

Errors use `application/problem+json`: 400 invalid input, 401 absent/expired/
revoked authentication, 403 insufficient access or invalid/missing CSRF,
404 absent resource, and 409 duplicate/conflicting/concurrent edits. Errors
never include credentials, tokens or rejected secret values.

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
complete current schema:

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
No live PostgreSQL/Keycloak instance or real credentials are needed.

An opt-in headless Chrome/Chromium browser test exercises consecutive
create/edit saves, table refreshes, exact packaging calculations, visible
validation errors and mobile overflow. It uses the same local signed OIDC
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

Next module: Inventory with early spoilage controls. Sales, payments,
accounting, farm tracking and AI remain outside Phase 2.
