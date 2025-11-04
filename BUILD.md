# Build Guide

## Prerequisites

- Java 21+
- Maven 3.9+
- Git

## Quick Start

```bash
# Clone the repository
git clone <repository-url>
cd cashu-voucher

# Build all modules
mvn clean install

# Run tests with coverage
mvn clean verify

# Deploy to maven.398ja.xyz (requires credentials)
mvn deploy
```

## Build Verification

### Phase 0 Build Verification (Completed 2025-11-04)

**Status**: ✅ All modules compile and install successfully

**Verification Steps**:
1. Clean build: `mvn clean install`
2. Verify reactor build order:
   - Cashu Voucher (parent POM)
   - Cashu Voucher Domain
   - Cashu Voucher Application
   - Cashu Voucher Nostr

**Generated Artifacts**:

Each module produces:
- Main JAR: `{module}-0.1.0.jar`
- Sources JAR: `{module}-0.1.0-sources.jar`
- Javadoc JAR: `{module}-0.1.0-javadoc.jar` (when code exists)

**Installed to Local Repository**:
```
~/.m2/repository/xyz/tcheeric/
  ├── cashu-voucher/0.1.0/
  │   └── cashu-voucher-0.1.0.pom
  ├── cashu-voucher-domain/0.1.0/
  │   ├── cashu-voucher-domain-0.1.0.jar
  │   ├── cashu-voucher-domain-0.1.0.pom
  │   └── cashu-voucher-domain-0.1.0-sources.jar
  ├── cashu-voucher-app/0.1.0/
  │   ├── cashu-voucher-app-0.1.0.jar
  │   ├── cashu-voucher-app-0.1.0.pom
  │   └── cashu-voucher-app-0.1.0-sources.jar
  └── cashu-voucher-nostr/0.1.0/
      ├── cashu-voucher-nostr-0.1.0.jar
      ├── cashu-voucher-nostr-0.1.0.pom
      └── cashu-voucher-nostr-0.1.0-sources.jar
```

**Build Time**: ~11 seconds (clean install)

**Test Results**:
- Total tests: 0 (no tests yet - skeleton phase)
- Code coverage: N/A (will enforce 80% minimum once code is added)

## Continuous Integration

### GitHub Actions

The project uses GitHub Actions for automated builds on every push and PR.

**Workflow**: `.github/workflows/build.yml`

**Triggers**:
- Push to `main` or `develop` branches
- Pull requests to `main` or `develop` branches

**Build Steps**:
1. Checkout code
2. Setup JDK 21 (Temurin distribution)
3. Build with Maven (uses cache)
4. Generate JaCoCo coverage reports
5. Check code coverage threshold (80%)
6. Upload artifacts (test results, coverage, JARs)

**Artifacts Retention**:
- Test results: 30 days
- JAR files: 7 days

## Code Quality

### JaCoCo Code Coverage

**Minimum Coverage**: 80% line coverage (enforced)

**Configuration**: Parent POM `pom.xml:290-303`

```xml
<limit>
    <counter>LINE</counter>
    <value>COVEREDRATIO</value>
    <minimum>0.80</minimum>
</limit>
```

**Commands**:
```bash
# Generate coverage report
mvn jacoco:report

# Check coverage threshold
mvn jacoco:check

# View report
open */target/site/jacoco/index.html
```

## Publishing

### Local Installation

```bash
# Install to ~/.m2/repository
mvn clean install
```

### Remote Deployment

**Repository**: https://maven.398ja.xyz

```bash
# Deploy to maven.398ja.xyz (requires credentials in settings.xml)
mvn clean deploy
```

**Required Settings** (`~/.m2/settings.xml`):
```xml
<servers>
  <server>
    <id>maven-398ja-xyz</id>
    <username>${env.MAVEN_USERNAME}</username>
    <password>${env.MAVEN_PASSWORD}</password>
  </server>
</servers>
```

## Module Build Order

Maven reactor builds in this order:

1. **cashu-voucher** (parent POM)
   - Defines dependency management
   - Configures all plugins
   - No artifacts produced

2. **cashu-voucher-domain**
   - Pure domain logic
   - No dependencies on app or nostr modules
   - Zero infrastructure dependencies

3. **cashu-voucher-app**
   - Depends on: domain
   - Application services and port interfaces

4. **cashu-voucher-nostr**
   - Depends on: domain, app
   - Nostr infrastructure adapter

## Troubleshooting

### Build Fails with "Cannot resolve dependencies"

Ensure you have access to the custom Maven repository:
```bash
# Check if repository is accessible
curl -I https://maven.398ja.xyz/releases
```

### JaCoCo Coverage Check Fails

This is expected during Phase 0 (no code yet). Once code is added:
```bash
# Skip coverage check during development
mvn clean verify -Djacoco.skip=true
```

### "Nostr dependencies not found"

Expected during Phase 0. Nostr dependencies are commented out in `cashu-voucher-nostr/pom.xml` until they become available.

## Build Status

**Last Verified**: 2025-11-04 20:18:08 UTC
**Build Result**: ✅ SUCCESS
**Total Time**: 11.483 seconds
**Modules Built**: 4/4

---

**Note**: This build guide will be updated as the project progresses through implementation phases.
