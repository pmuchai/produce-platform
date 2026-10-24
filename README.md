# Produce Platform

A modular fresh-produce platform for branch operations, inventory, sales and
spoilage control. The repository currently contains the Phase 0 backend
foundation, not the business modules or a frontend.

## Requirements

- JDK 25, with `JAVA_HOME` pointing to the JDK.
- The included Maven Wrapper; a separate Maven installation is not required.
- Local PostgreSQL for development. Docker is not required.
- H2 is used only by automated tests and needs no separate installation.

Check the toolchain in PowerShell:

```powershell
java -version
.\mvnw.cmd -version
```

## Local PostgreSQL setup

Provision a dedicated development database and a non-superuser application role
using your local PostgreSQL administration tool. The defaults are database
`primeroot_platform` and role `primeroot_app`. The role must be able to connect
and create migration objects in the intended schema. Reuse an existing database
only after checking its tables and Flyway history; do not drop or reset it.

Verify the application login, entering the password only at the prompt:

```powershell
psql -h localhost -p 5432 -U primeroot_app -d primeroot_platform -W -c "SELECT current_database(), current_user;"
```

If `psql` is not on PATH, test the same connection in IntelliJ or pgAdmin.
An authentication failure must be resolved in the local PostgreSQL role,
password or connection settings, not by disabling authentication.

### Configure and run

Set these process environment variables in the same PowerShell terminal used
to start the application:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/primeroot_platform"
$credential = Get-Credential -UserName "primeroot_app" -Message "Local PostgreSQL application login"
$env:DB_USERNAME = $credential.UserName
$env:DB_PASSWORD = $credential.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

`DB_URL` and `DB_USERNAME` have the defaults shown above. `DB_PASSWORD` is
required and has no fallback. Never commit real credentials. All `.env` files,
including `.env.example`, are ignored by Git. Keep any local configuration
private and use the variable names documented above. Spring Boot does **not**
automatically load `.env` files.

For IntelliJ, use JDK 25 and set these variables in the
`ProducePlatformApplication` run configuration. Do not share a run configuration
containing credentials.

After startup, check from another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

The health endpoint returns `UP` only when its health checks, including the
database, pass. It does not expose component details. Only the health Actuator
endpoint is exposed over HTTP. Other application requests retain Spring
Security's default authentication requirement. The generated development user
is not the planned staff/roles authentication system.

Stop with Ctrl+C. Remove the password from the terminal environment when done:

```powershell
Remove-Item Env:DB_PASSWORD
Remove-Variable credential
```

## Automated tests

```powershell
.\mvnw.cmd clean verify
```

The application test explicitly activates the `test` profile. Its configuration
exists only under `src\test\resources` and overrides all four datasource settings
with an isolated, in-memory H2 database. Local PostgreSQL and `DB_*` variables
are not needed for tests. H2 is a test-scoped dependency and is not bundled in
the executable application.

Tests start a real HTTP server on a random port and check application startup,
the in-memory datasource, anonymous health access and authentication on other
requests. Flyway remains enabled and Hibernate remains in validation mode;
tests do not replace migration management with Hibernate schema generation.

H2 runs in PostgreSQL compatibility mode, but it is **not** PostgreSQL. These
tests do not establish PostgreSQL-specific SQL, constraint, locking or migration
compatibility. Validate future database-dependent modules against PostgreSQL
before release as well.

## Schema safety and current scope

Flyway owns schema changes; Hibernate uses `ddl-auto=validate` and never creates
or updates tables. Flyway clean is disabled. Flyway may initialize its schema
history table on startup, but there are currently no versioned migrations,
entities or business tables. An empty migration set is intentional in Phase 0;
it is not evidence that a future business schema has been validated.

The first migration should implement the approved Branches and Access model in
Phase 1 under `src\main\resources\db\migration`. Preserve any applied migration
history and use incremental migrations thereafter. Do not enable automatic
baselining or destructive database resets to bypass schema conflicts.

Next modules: Branches and Access, Catalogue and Units, then Inventory with
early spoilage controls. Sales, payments, vendor accounting, farm tracking and
AI are outside Phase 0.