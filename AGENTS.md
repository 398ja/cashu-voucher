# cashu-voucher Agent Guide

cashu-voucher is a Java 21 multi-module project that implements a gift-card style voucher system for the Cashu ecash protocol, with optional Nostr-based storage and discovery. This guide captures the repository conventions that agents must follow when extending or reviewing the project.

## Protocol & Standards
- Cashu NUTs (specification index: https://github.com/cashubtc/nuts)
  - NUT-00 – Token formats (cashuA/cashuB serialization)
  - NUT-02 – Keysets (identifiers, key ordering, discovery)
  - NUT-09 – Restore (payloads for restore-related flows)
  - NUT-13 – Deterministic secrets (mnemonics, counters, keyset binding)
- Nostr NIPs (as applicable to the Nostr adapter)
  - NIP-17 – Encrypted direct messages
  - NIP-33 – Parameterized replaceable events
  - NIP-44 – Encrypted payload scheme (v2)

When behaviour intersects these specifications, review the relevant documents and ensure conformity. Prefer deterministic, stable serialization and signature inputs to avoid incompatibilities.

## Repository Layout
- `pom.xml`: parent POM pinning dependency and plugin versions, aggregating modules, and configuring JaCoCo.
- `cashu-voucher-domain/`: pure domain logic for vouchers (no infrastructure dependencies). Business rules, deterministic serialization helpers, and crypto boundaries live here.
- `cashu-voucher-app/`: application services and port interfaces. Defines use-cases and infrastructure-agnostic contracts.
- `cashu-voucher-nostr/`: Nostr infrastructure adapter implementing the ports (event kinds/tags, encryption, and relay I/O).
- `project/`: design notebooks, protocol analysis, voucher plans, and long-form documentation copied from cashu-lib for local reference.
- `README.md`: high-level overview and usage.
- `LICENSE`: project license.

## Tooling & Build
- Target Java 21 (Temurin). Compiler release set to 21 in the parent POM.
- Use Maven for builds:
  - Full build + tests: `mvn -q verify`
  - Module build: `mvn -q -pl <module> -am verify`
  - Coverage report: `mvn -q jacoco:report`
- JaCoCo is configured with an 80% LINE coverage rule at the bundle level.
- Dependencies are centrally managed in the root `dependencyManagement`. Add versions there and import from child modules.

## Coding
- Clean Code and Clean Architecture principles guide design:
  - Clean Code: https://dev.398ja.xyz/books/Clean_Architecture.pdf
  - Clean Architecture: https://dev.398ja.xyz/books/Clean_Code.pdf
- Design Patterns reference: https://github.com/iluwatar/java-design-patterns
- Prefer imports over fully qualified names in code to keep implementations readable.
- Follow Conventional Commits: https://www.conventionalcommits.org/en/v1.0.0/
- Ensure features remain compliant with the Cashu NUTs and relevant Nostr NIPs listed above.

## Module Guidelines

### Domain (`cashu-voucher-domain`)
- Purpose: implement core voucher entities, value objects, and domain services. No dependencies on transport, storage, Nostr, or process clocks beyond explicit abstractions.
- Determinism:
  - Keep serialization canonical and stable (especially for CBOR/JSON used in token or voucher payloads).
  - Avoid non-determinism in business logic; inject time and randomness via interfaces to enable reproducibility in tests.
- Crypto boundaries:
  - Use Bouncy Castle (`bcprov-jdk18on`) via clearly named helpers. Keep cryptographic details behind domain or utility abstractions.
  - Validate inputs (lengths, encodings, curve domain parameters). Fail fast on malformed data.
- Data validation:
  - Validate domain invariants at construction time (amount ranges, expiry semantics, counter progression).
  - Prefer immutability for domain objects. Use builders or factory methods where helpful.
- Exceptions & errors:
  - Use meaningful, domain-scoped exceptions. Do not expose infrastructure exceptions in domain APIs.
- Logging:
  - Keep domain logging minimal; prefer returning rich error information instead of logging internals.

Model B constraint:
- Vouchers are not redeemable at the mint. Any attempt to use voucher material in melt/swap-like flows must be rejected with a domain-appropriate error. Redemption occurs with the issuing merchant only.

### Application (`cashu-voucher-app`)
- Purpose: orchestrate domain use-cases and define port interfaces for infrastructure (e.g., Nostr relay I/O, persistence, clock, entropy).
- Ports & adapters:
  - Define clear interfaces for outbound dependencies (publish/store/load, encrypt/decrypt, time/randomness).
  - Keep method signatures small and explicit; avoid leaking implementation types from adapters into application code.
- Serialization:
  - Use Jackson for JSON/CBOR mapping when needed, but keep mapping concerns at the edges (DTOs in adapters). Application services should operate on domain types.
- Testing:
  - Favor state-based tests over interaction tests; where mocking is needed, mock only ports.

### Nostr Adapter (`cashu-voucher-nostr`)
- Purpose: implement application ports for Nostr. Responsible for event construction, tagging, encryption (NIP-17/NIP-44), and parameterized replaceable events (NIP-33).
- Isolation:
  - Do not depend on UI/CLI concerns. Keep concurrency and IO lifecycle local to the adapter.
- Event model:
  - Centralize event kind constants and tag formats. Provide stable builders for repeatability in tests.
- Crypto & keys:
  - Manage key derivation and encryption through well-defined services. Do not copy crypto logic from domain; share abstractions where sensible.
- Resilience:
  - Treat network/relay failures as recoverable. Surface retries and backoff via adapter policies; do not leak transient failures into domain/app layers.

Event kinds and tags (align with README):
- Public Ledger (NIP-33):
  - Kind: `30078` (parameterized replaceable)
  - Tag: `["d", "voucher:<voucherId>"]`
- Private Backup (NIP-17 + NIP-44):
  - Kind: `14` (direct message)
  - Encryption: NIP-44 (ChaCha20-Poly1305)

## Serialization & Formats
- JSON and CBOR are used via Jackson. When adding fields:
  - Preserve backward compatibility (additive changes, default values) unless a major version is warranted.
  - Keep property names snake_case or camelCase consistently within a given DTO family. Align with Cashu NUT expectations when representing protocol entities.
- For token- or voucher-like payloads, ensure canonical ordering where required by the spec before signing or hashing.

## Testing
- Frameworks: JUnit 5, Mockito, AssertJ.
- Commands: `mvn -q verify` runs the full suite with coverage. Use `-pl`/`-am` for module-scoped runs.
- Guidelines:
  - Unit tests should not hit network, filesystem, or real relays. Mock ports in the application layer; use pure domain tests otherwise.
  - Prefer deterministic seeds for random data; inject clocks and entropy sources.
  - Cover happy paths and edge cases (invalid encodings, boundary amounts, expired vouchers, key mismatches).
  - Keep tests fast and isolated. Avoid order dependencies.

## Error Handling
- Domain layer:
  - Use domain-scoped exception types with precise messages. Validate invariants eagerly; fail fast on malformed input.
  - Do not leak infrastructure exceptions; wrap and map to domain/application errors.
- Application layer:
  - Convert domain exceptions to result types or well-defined checked/unchecked exceptions suitable for adapters.
  - Include stable error codes where user-facing systems are expected to react (e.g., `VOUCHER_NOT_ACCEPTED` for Model B).
- Nostr adapter:
  - Treat relay/network errors as transient; implement retries/backoff and surface actionable context to callers without exposing low-level details.
  - Timeouts and cancellation should be configurable via adapter policies or method parameters.

## Logging
- Use SLF4J; avoid logging secrets, voucher material, or private keys.
- Levels:
  - ERROR: irrecoverable failures after retries; include voucher or event identifiers (not secrets).
  - WARN: transient issues, partial failures, or fallback paths.
  - INFO: lifecycle events (startup, connection established, release info).
  - DEBUG: protocol details useful during development (event kinds, tag shapes) without sensitive payloads.
- Structure logs with stable fields: `voucherId`, `merchantId`, `nostrKind`, `relay`, `keysetId`.

## Documentation
- Keep `README.md` aligned with current module responsibilities, key classes, CLI commands, and Nostr event kinds/tags.
- Public APIs (domain/app ports) should have Javadoc explaining invariants, threading expectations, and error semantics.
- This repo does not include `docs/` or `project/`; defer to cashu-lib’s documentation for deep protocol and planning material and link from `README.md` when relevant.

## Versioning & Release
- Versioning: follow Semantic Versioning. Keep all modules on the same version (`pom.xml` parent and child inherit).
- Dependencies: `cashu-lib.version` is pinned in the parent POM; update in one place when bumping.
- Distribution: artifacts are configured to deploy to `https://maven.398ja.xyz` (releases) and `/snapshots` via `distributionManagement` in the parent POM.
- Release flow (manual baseline):
  - Ensure tests pass and coverage meets threshold: `mvn -q verify`.
  - Bump `<version>` in the parent POM (propagates to modules), update `README.md` status/version.
  - Tag the release `vX.Y.Z` and publish artifacts if credentials are configured: `mvn -q -DskipTests deploy`.
  - For snapshot development, append `-SNAPSHOT` to the parent version.
- If adopting automation later (e.g., release-please), avoid hand-editing generated sections and follow the tool’s workflow.

## Project Research Notes
- Authoritative research and plans live in cashu-lib’s `project/` directory (linked from this repo’s `README.md`). Key documents include:
  - `gift-card-plan-final-v2.md` – Implementation plan
  - `voucher-architecture-diagrams.md` – Architecture diagrams
  - `voucher-test-plan.md` – Test specifications
  - `voucher-deployment-guide.md` – Deployment guide
  - `voucher-api-specification.md` – API reference
- Consult these when implementing or reviewing Model B logic, Nostr storage flows, and deterministic secret handling (NUT-13).

## Style & Conventions
- Language: Java 21, `-parameters` compiler arg enabled.
- Null-handling: prefer `Optional` for absence in APIs; validate inputs with `Objects.requireNonNull`.
- Immutability: default to `final` fields and unmodifiable collections.
- Logging: use SLF4J. Keep logging out of hot paths; avoid logging secrets.
- Lombok: allowed for boilerplate reduction; avoid complex Lombok features that obscure control flow.

## Dependency Management
- Add or bump versions in the root `dependencyManagement`. Child modules should reference artifacts without hardcoding versions.
- Keep transitive crypto and Jackson versions consistent with the parent to prevent classpath conflicts.

## Release & Commits
- Conventional Commits for all changes. Examples:
  - `feat(domain): add voucher expiry policy`
  - `fix(nostr): correct NIP-33 tag ordering`
  - `test(app): add edge case for counter reset`
  - `chore: bump jackson to 2.17.0`
- Write commit messages in present tense and include scope when practical.

## Agent Notes
- The scope of this AGENTS.md is the entire repository. Instructions here apply to all modules.
- When editing files, keep changes scoped and aligned with the existing structure and naming patterns.
- If you add new DTOs or protocol-relevant structures, ensure their serializers/deserializers are included and tested.
 - Cross-reference `README.md` for current module responsibilities, key classes, and CLI commands.

Project documentation
- The `project/` directory captures ongoing protocol work (voucher compatibility, CLI integration, release phases). Review these documents when implementing Model B, Nostr storage, or NUT-13 features to stay aligned with the planned architecture.
- Keep `project/README.md` updated when adding or renaming documents.

## Pre-Submit Checklist
- Code follows module guidelines above and maintains deterministic behaviour where required.
- New DTOs or domain types include validation and, if needed, JSON/CBOR mapping updates.
- Tests cover happy and edge paths, and pass locally via `mvn -q verify` with coverage meeting the JaCoCo rule.
- Documentation (README or module-level docs) is updated for new behaviour.
- Commits follow Conventional Commits with clear scope and intent.
