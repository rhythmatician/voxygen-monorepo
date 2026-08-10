# Gradle & Dependency Troubleshooting Guide

## 🚨 Common Issues and Solutions

### Issue: File Locking During Dependency Resolution

#### Symptoms
```
Previous process has disowned the lock due to abrupt termination.
Found existing cache lock file (ACQUIRED_PREVIOUS_OWNER_DISOWNED)
```

#### Diagnosis
- VS Code Java extension is downloading/resolving dependencies
- File locks are **normal** during this process
- Duration: 4-5 minutes for complex dependencies like Hephaistos

#### Solution
1. **Do NOT interrupt**: Let VS Code complete the sync
2. **Monitor progress**: Check "Output" → "Gradle for Java" 
3. **Wait for**: `CONFIGURE SUCCESSFUL in Xm Ys` message
4. **Verify**: Look for `Found X tasks` confirmation

### Issue: Hephaistos Dependency Resolution Failed

#### Symptoms
```
Could not resolve: com.github.jglrxavpok:Hephaistos:X.X.X
Repository not found
```

#### Root Cause
- Repository moved from `jglrxavpok` to `Minestom`
- Old dependency path no longer valid

#### Solution
Update `build.gradle`:
```gradle
dependencies {
    // ✅ Correct (use this):
    implementation 'com.github.Minestom:Hephaistos:2.1.2'
    
    // ❌ Incorrect (outdated):
    // implementation 'com.github.jglrxavpok:Hephaistos:...'
}
```

### Issue: Unresolved Imports After Dependency Added

#### Symptoms
```java
import io.github.minestom.hephaistos.nbt.*;  // Cannot resolve
```

#### Diagnosis Steps
1. Check if Gradle sync completed successfully
2. Verify `CONFIGURE SUCCESSFUL` appeared in Gradle output
3. Confirm correct dependency version in `build.gradle`

#### Solution
- **If sync incomplete**: Wait for completion (don't interrupt)
- **If sync successful but imports unresolved**: Restart VS Code Java extension
- **If persistent**: Clean and rebuild

### Issue: Persistent Build Cache Corruption

#### Nuclear Option (Last Resort)
```bash
# Stop all Gradle processes
./gradlew --stop

# Clean project-specific cache
rm -rf .gradle build

# Clean global Fabric Loom cache (if using Fabric)
rm -rf ~/.gradle/caches/fabric-loom

# Clean rebuild with fresh dependencies
./gradlew clean build --refresh-dependencies
```

## ⏰ Expected Timelines

| Operation | Expected Duration | Warning Threshold |
|-----------|-------------------|-------------------|
| Simple dependency resolution | 30-60 seconds | > 2 minutes |
| Complex dependency (Hephaistos) | 4-5 minutes | > 10 minutes |
| Full clean build | 2-3 minutes | > 5 minutes |
| Loom cache rebuild | 5-10 minutes | > 15 minutes |

## 🔍 Monitoring Commands

### Check Gradle Status
```bash
# Show running Gradle daemons
./gradlew --status

# Stop all daemons
./gradlew --stop

# Force refresh dependencies
./gradlew build --refresh-dependencies
```

### VS Code Specific
1. **Command Palette** (`Ctrl+Shift+P`):
   - "Java: Reload Projects"
   - "Java: Restart Language Server"
2. **Output Panel**: Select "Gradle for Java" to monitor sync progress

## 🚫 What NOT to Do

- ❌ Don't kill VS Code during dependency resolution
- ❌ Don't manually delete `.gradle` during active sync
- ❌ Don't restart Gradle daemon while "Building workspace" is shown
- ❌ Don't assume file locks mean failure (they're often normal)

## ✅ Best Practices

- ✅ Let dependency resolution complete fully
- ✅ Monitor VS Code Gradle output for progress
- ✅ Wait for explicit `CONFIGURE SUCCESSFUL` confirmation
- ✅ Only clean caches after confirmed sync failure
- ✅ Test with simple `./gradlew tasks` after dependency changes
