package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FlightLoopDiagnosticContractTest {
    @Test
    void launcherForwardsRefinementModeAndSanitizesCopiedAndCapturedVoxyStores() throws Exception {
        Path javaRoot = Path.of("").toAbsolutePath().normalize();
        String script = Files.readString(javaRoot.getParent().resolve("flight-loop.ps1"));
        String gradle = Files.readString(javaRoot.resolve("build.gradle"));

        assertTrue(script.contains("[switch]$DisableRefinementAdmission"));
        assertTrue(script.contains("-PdisableRefinementAdmission="));
        assertTrue(gradle.contains("lodiffusion.test.disableRefinementAdmission"));
        assertTrue(script.contains("Remove-ValidatedTree $copiedVoxyStore $liveWorld"));
        assertTrue(script.contains("Remove-ValidatedTree $capturedVoxyStore $templateDir"));
    }
}
