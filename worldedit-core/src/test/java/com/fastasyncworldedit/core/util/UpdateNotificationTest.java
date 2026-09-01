package com.fastasyncworldedit.core.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class UpdateNotificationTest {

    @ParameterizedTest
    @CsvSource({"1.0.0,2.0.0", "1.0.0,1.1.0", "1.0.0,1.0.1", "1.0.0,1.0.0.1"})
    void hasUpdateSemver_true(String installed, String latest) {
        assertTrue(UpdateNotification.hasUpdateSemver(
                Arrays.stream(installed.split("\\.")).mapToInt(Integer::parseInt).toArray(),
                Arrays.stream(latest.split("\\.")).mapToInt(Integer::parseInt).toArray()
        ));
    }

    @ParameterizedTest
    @CsvSource({"1.0.0,1.0.0", "2.0.0,1.9.9", "1.0.0,1.0", "1.0,1.0.0"})
    void hasUpdateSemver_false(String installed, String latest) {
        assertFalse(UpdateNotification.hasUpdateSemver(
                Arrays.stream(installed.split("\\.")).mapToInt(Integer::parseInt).toArray(),
                Arrays.stream(latest.split("\\.")).mapToInt(Integer::parseInt).toArray()
        ));
    }

    @ParameterizedTest
    @CsvSource({"2.15.5-folia.4,4,2.15.5-folia.5,5", "2.15.5-folia.4,4,2.15.6-folia.1,1", "2.15.5-folia.4,4,2.16.0,0"})
    void hasUpdate_true(String installedVersion, int installedBuild, String latestVersion, int latestBuild) {
        assertTrue(UpdateNotification.hasUpdate(semver(installedVersion), installedBuild, releaseTag(latestVersion, latestBuild)));
    }

    @ParameterizedTest
    @CsvSource({"2.15.5-folia.4,4,2.15.5-folia.4,4", "2.15.5-folia.5,5,2.15.5-folia.4,4", "2.15.6-folia.1,1,2.15.5-folia.9,9"})
    void hasUpdate_false(String installedVersion, int installedBuild, String latestVersion, int latestBuild) {
        assertFalse(UpdateNotification.hasUpdate(semver(installedVersion), installedBuild, releaseTag(latestVersion, latestBuild)));
    }

    private static int[] semver(String version) {
        String semver = version.split("-")[0];
        return Arrays.stream(semver.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static int[] releaseTag(String tag, int build) {
        String semver = tag.split("-")[0];
        return UpdateNotification.parseReleaseTag(semver, build == 0 ? null : Integer.toString(build));
    }

}
