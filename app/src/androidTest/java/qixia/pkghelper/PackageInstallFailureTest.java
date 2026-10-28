package qixia.pkghelper;

import org.junit.Test;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PackageInstallFailureTest {
    @Test public void equalVersionAndSizeCannotHideDifferentApk() throws Exception {
        File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getCacheDir().toPath(), "apk-identity-").toFile();
        File installed = new File(root, "installed.apk");
        File incoming = new File(root, "incoming.apk");
        try {
            Files.write(installed.toPath(), "debug-one".getBytes(StandardCharsets.UTF_8));
            Files.write(incoming.toPath(), "release-v".getBytes(StandardCharsets.UTF_8));
            PackageInfo info = new PackageInfo();
            info.setLongVersionCode(206); info.versionName = "v2.0.6";
            info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.sourceDir = installed.getAbsolutePath();
            String expected = PackageHelper.sha256File(incoming);
            assertEquals(installed.length(), incoming.length());
            assertFalse(PackageHelper.installedApkMatches(info, expected));
            Files.write(installed.toPath(), Files.readAllBytes(incoming.toPath()));
            assertTrue(PackageHelper.installedApkMatches(info, expected));
            info.applicationInfo.splitSourceDirs = new String[]{"additional.apk"};
            assertFalse(PackageHelper.installedApkMatches(info, expected));
        } finally {
            installed.delete(); incoming.delete(); root.delete();
        }
    }

    @Test public void detectsReservedSpaceAndFullDiskFailures() {
        assertTrue(PackageHelper.isStorageFailure(
            "Error: failed to write; Failed to allocate 58072978 because only 0 allocatable"));
        assertTrue(PackageHelper.isStorageFailure("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"));
        assertTrue(PackageHelper.isStorageFailure("java.io.IOException: No space left on device"));
        assertTrue(PackageHelper.isStorageFailure("Requested internal only, but not enough space"));
    }

    @Test public void otherErrorsStillAllowInstallerFallback() {
        assertFalse(PackageHelper.isStorageFailure(null));
        assertFalse(PackageHelper.isStorageFailure("Success"));
        assertFalse(PackageHelper.isStorageFailure("Unknown command: install-create"));
        assertFalse(PackageHelper.isStorageFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"));
        assertFalse(PackageHelper.isStorageFailure("Failed to allocate memory"));
    }
}
