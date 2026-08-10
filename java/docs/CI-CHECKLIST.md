# CI Checklist for LODiffusion

This checklist ensures code quality and proper integration practices for the LODiffusion Minecraft mod, following the **micro-commit strategy** outlined in `.github/copilot-instructions.md`.

## Micro-Commit Workflow Checklist

### Branch Management
- ✅ **Focused branch created** with specific naming:
  - `test/add-xyz-test` for test additions
  - `feat/implement-abc` for feature implementation  
  - `fix/resolve-def` for bug fixes
  - `docs/update-ghi` for documentation updates
- ✅ **Single logical change** targeted (max 200 lines of changes)
- ✅ **Branch freshness**: created from latest `main` after `git pull`
- ✅ **Clean working tree** before starting (`git status` shows no uncommitted changes)

### Commit Discipline
- ✅ **Frequent commits** (every 15-20 minutes during active development)
- ✅ **Conventional commit prefixes** used:
  - `test:` for test additions
  - `feat:` for features
  - `fix:` for bugfixes
  - `docs:` for documentation
- ✅ **One logical change per commit**:
  - Adding 1-2 test methods → single commit
  - Fixing one compilation issue → single commit
  - Updating one documentation section → single commit
- ✅ **Pushed frequently** for backup and CI validation

### PR Requirements
- ✅ **Reviewable in < 10 minutes** (focused scope)
- ✅ **Auto-merge eligible** for docs/CI changes:
  - Only `docs/`, `*.md`, or `.github/workflows/*.yml` files modified
  - < 200 lines of code changed
  - All CI checks passing (lint, test, build)
  - No unresolved review threads
- ✅ **Documentation updated** (`docs/PROJECT-OUTLINE.md`, `docs/CI-CHECKLIST.md` if relevant)

## Pre-Commit Checklist

### Dependency Management
- ✅ `build.gradle` dependency scope verified (compileOnly vs implementation)
  - Fabric dependencies: `modImplementation`
  - Test dependencies: `testImplementation`
  - Optional integrations: use runtime detection, not compile-time dependencies
- ✅ No unnecessary dependencies added
- ✅ Version pinning maintained for reproducible builds

### Code Quality
- ✅ Remove speculative DH files (placeholder implementations cleaned up)
- ✅ No unresolved imports or phantom classes
- ✅ All compilation warnings addressed
- ✅ Lint task passes (`./gradlew lint`)
  - Verifies enhanced compiler warnings (-Xlint:all)
  - Treats warnings as errors (-Werror)
  - Ensures clean compilation with strict analysis
- ✅ Code follows project conventions and style guidelines
- ✅ **Problems tab in IDE cleared of critical issues**:
  - ❌ No `cannot be resolved to a type` errors
  - ❌ No missing import statements
  - ❌ No references to non-existent classes (e.g., `DHWorldGeneratorOverride`)
  - ❌ No unused declared fields or variables
- ✅ **Reflection wrapper validation**:
  - All reflection method references point to actual methods
  - Unused reflection fields are removed or documented
  - Reflection fallback paths are tested

### Integration Safety
- ✅ Reflection paths covered with `ModDetection.isModLoaded("distanthorizons")`
- ✅ Optional mod integrations use safe detection patterns
- ✅ No hard dependencies on external mods (except Fabric API)
- ✅ Graceful fallback behavior when optional mods are not present

### Testing & Coverage
- ✅ All tests pass (`./gradlew test`)
- ✅ JaCoCo coverage ≥ 70% (`./gradlew jacocoTestCoverageVerification`)
- ✅ New features have corresponding tests
- ✅ No test dependencies on Minecraft bootstrap (use mocks appropriately)

### Documentation & Planning
- ✅ All TODOs logged or tracked in `docs/PROJECT-OUTLINE.md`
- ✅ Public API methods have JavaDoc comments
- ✅ README.md updated with new features
- ✅ CHANGELOG.md updated for user-facing changes

### Git & CI
- ✅ Commit messages follow conventional format (`feat:`, `fix:`, `test:`, `docs:`)
- ✅ One logical change per commit (micro-commit strategy)
- ✅ Frequent commits during development (every 15-20 minutes)
- ✅ Branch naming follows convention (`test/`, `feat/`, `fix/`, `docs/` prefixes)
- ✅ No merge conflicts
- ✅ Branch builds successfully in CI
- ✅ Ready for auto-merge (< 200 lines changed, reviewable in < 10 minutes)

## Automated Checks

The CI pipeline runs three separate jobs for better parallelization and clearer feedback:

### 1. Lint Job (Code Quality & Linting)
```bash
# Runs first and fastest - fails quickly on style issues
./gradlew lint
```
- Verifies enhanced compiler warnings (-Xlint:all)
- Treats warnings as errors (-Werror)
- Ensures clean compilation with strict analysis
- **Fails fast** to provide immediate feedback on code quality

### 2. Test Job (Tests & Coverage)
```bash
# Runs in parallel with lint job
./gradlew test jacocoTestReport jacocoTestCoverageVerification
```
- Executes all unit tests
- Generates coverage reports
- Enforces ≥ 70% coverage threshold
- Comments coverage results on PRs
- Uploads coverage artifacts

### 3. Build Job (Build Mod)
```bash
# Only runs if lint and test jobs pass
./gradlew build --exclude-task test
```
- Builds the final mod JAR
- Runs only after lint and test pass (via `needs: [lint, test]`)
- Uploads build artifacts
- Validates that the mod can be packaged successfully

### Local Development Workflow
For the complete check before pushing (matches all CI jobs):
```bash
# Run all checks locally before push
./gradlew clean lint test jacocoTestReport jacocoTestCoverageVerification build

# Static analysis for common problems
echo "🔍 Checking for compilation issues..."

# Check for unresolved imports and missing classes
./gradlew compileJava 2>&1 | tee compile.log
if grep -E "cannot be resolved|not found|does not exist" compile.log; then
    echo "❌ Found unresolved imports or missing classes"
    exit 1
fi

# Check for unused imports
./gradlew compileJava -Xlint:all 2>&1 | grep "unused" && {
    echo "⚠️  Found unused imports - should be cleaned up"
}

# Check for phantom class references
echo "🔍 Scanning for phantom class references..."
find src/main/java -name "*.java" -exec grep -l "DHWorldGeneratorOverride\|Class\.forName.*distanthorizons.*" {} \; | while read file; do
    echo "⚠️  Potential phantom class reference in: $file"
done

# Validate reflection wrapper integrity
echo "🔍 Validating reflection wrappers..."
find src/main/java -name "*Compat.java" -o -name "*Wrapper.java" | while read file; do
    # Check for unused private fields (basic heuristic)
    grep -n "private.*Field\|private.*Method" "$file" | while read line; do
        field_name=$(echo "$line" | sed -n 's/.*private.*\s\+\([a-zA-Z_][a-zA-Z0-9_]*\)\s*[;=].*/\1/p')
        if [ -n "$field_name" ] && ! grep -q "$field_name" "$file" | grep -v "private.*$field_name"; then
            echo "⚠️  Potentially unused reflection field '$field_name' in $file:$(echo $line | cut -d: -f1)"
        fi
    done
done

echo "✅ Static analysis checks completed"
```

### IDE Integration Checks

For VS Code users, these additional checks help catch Problems tab issues:

```bash
# Check Java Language Server diagnostics
echo "🔍 Checking for IDE diagnostics..."

# Look for common compilation issues in source files
find src -name "*.java" -exec grep -l "import.*\.\*" {} \; | while read file; do
    echo "📝 File with wildcard imports (may cause resolution issues): $file"
done

# Check for missing package declarations
find src/main/java src/test/java -name "*.java" | while read file; do
    if ! head -10 "$file" | grep -q "package "; then
        echo "❌ Missing package declaration: $file"
    fi
done

# Validate class names match file names
find src -name "*.java" | while read file; do
    filename=$(basename "$file" .java)
    if ! grep -q "class $filename\|interface $filename\|enum $filename" "$file"; then
        echo "⚠️  Class name may not match filename: $file"
    fi
done
```

## Integration-Specific Checks

### Distant Horizons Integration
- ✅ All DH integration uses `DistantHorizonsCompat` layer
- ✅ `ModDetection.isDistantHorizonsAvailable()` used before DH calls
- ✅ Proper fallback to `DefaultLODQuery` when DH not available
- ✅ No direct imports of DH classes (use reflection when needed)

### Fabric Integration
- ✅ Mod initializer properly registered in `fabric.mod.json`
- ✅ Mixins properly configured (if any)
- ✅ Event handlers properly registered
- ✅ No blocking operations on main thread

## Release Checklist

Additional checks for release preparation:

- ✅ Version number updated in `gradle.properties`
- ✅ `fabric.mod.json` version matches
- ✅ All deprecation warnings addressed
- ✅ Performance testing completed
- ✅ Documentation updated
- ✅ Release notes prepared

## Tools Integration

Future enhancements for the lint task:

### SpotBugs
```groovy
plugins {
    id 'com.github.spotbugs' version '5.0.14'
}

spotbugs {
    toolVersion = '4.7.3'
    effort = 'max'
    reportLevel = 'medium'
}
```

### Checkstyle
```groovy
plugins {
    id 'checkstyle'
}

checkstyle {
    toolVersion = '10.12.1'
    configFile = file('config/checkstyle/checkstyle.xml')
}
```

### PMD
```groovy
plugins {
    id 'pmd'
}

pmd {
    toolVersion = '6.55.0'
    ruleSetFiles = files('config/pmd/ruleset.xml')
}
```
