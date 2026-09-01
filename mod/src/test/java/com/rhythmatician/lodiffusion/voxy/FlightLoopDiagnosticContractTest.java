package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FlightLoopDiagnosticContractTest {
    private static String readFlightScript(Path repoRoot) throws Exception {
        Path p = repoRoot.resolve("flight-loop.ps1");
        if (!Files.exists(p)) p = repoRoot.resolve("flight.ps1");
        if (!Files.exists(p)) p = repoRoot.resolve("dev/flight/flight.ps1");
        if (!Files.exists(p)) p = repoRoot.resolve("flight-loop.ps1");
        String content = Files.readString(p);
        // delegation wrapper at repo root forwards to dev/flight/flight.ps1 — follow it
        if (content.contains("dev/flight/flight.ps1") && p.getFileName().toString().equals("flight.ps1")) {
            Path dev = repoRoot.resolve("dev/flight/flight.ps1");
            if (Files.exists(dev)) return Files.readString(dev);
        }
        return content;
    }

    @Test
    void launcherForwardsRefinementModeAndSanitizesCopiedAndCapturedVoxyStores() throws Exception {
        Path javaRoot = Path.of("").toAbsolutePath().normalize();
        String script = readFlightScript(javaRoot.getParent());
        String gradle = Files.readString(javaRoot.resolve("build.gradle"));

        assertTrue(script.contains("[switch]$DisableRefinementAdmission"));
        assertTrue(script.contains("-PdisableRefinementAdmission="));
        assertTrue(gradle.contains("lodiffusion.test.disableRefinementAdmission"));
        assertTrue(script.contains("Remove-ValidatedTree $copiedVoxyStore $liveWorld"));
        assertTrue(script.contains("Remove-ValidatedTree $capturedVoxyStore $templateDir"));
    }

    @Test
    void launcherRemovesOnlyStaleTourWaypointImagesInsideItsScreenshotDirectory() throws Exception {
        Path javaRoot = Path.of("").toAbsolutePath().normalize();
        String script = readFlightScript(javaRoot.getParent());

        assertTrue(script.contains("Resolve-ValidatedPath $screenshotsDir $validatedRunDir"));
        assertTrue(script.contains("Get-ChildItem -LiteralPath $validatedScreenshotsDir -File -Filter \"tour-waypoint-*.png\""));
        assertTrue(script.contains("Remove-Item -LiteralPath $_.FullName -Force"));
    }

    @Test
    void launcherUsesAnOverridableWaypointCountInsteadOfAHiddenRouteLength() throws Exception {
        Path javaRoot = Path.of("").toAbsolutePath().normalize();
        String script = readFlightScript(javaRoot.getParent());

        assertTrue(script.contains("[int]$WaypointCount"));
        assertTrue(script.contains("$WaypointCount -le 0"));
        assertTrue(script.contains("$idx -le $WaypointCount"));
        assertTrue(!script.contains("$idx -le 6"));
    }

    @Test
    void launcherDeploysTheCurrentModBeforeStartingTheClient() throws Exception {
        Path javaRoot = Path.of("").toAbsolutePath().normalize();
        String script = readFlightScript(javaRoot.getParent());
        String gradle = Files.readString(javaRoot.resolve("build.gradle"));

        int deploy = script.indexOf("\"deployToRunMods\"");
        int client = script.indexOf("\"runClient\"");
        assertTrue(deploy >= 0);
        assertTrue(client > deploy);
        assertTrue(gradle.contains("tasks.named('runClient').configure"));
        assertTrue(gradle.contains("dependsOn tasks.named('deployToRunMods')"));
    }
}
