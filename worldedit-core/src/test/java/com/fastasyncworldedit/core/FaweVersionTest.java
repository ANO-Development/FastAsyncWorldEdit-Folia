package com.fastasyncworldedit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaweVersionTest {

    @Test
    void parsesForkReleaseVersion() {
        FaweVersion version = FaweVersion.tryParse("version=2.15.5-folia.4", "commit=-3158959", "date=26.08.31");

        assertArrayEquals(new int[]{2, 15, 5}, version.semver);
        assertEquals(4, version.build);
        assertFalse(version.snapshot);
        assertEquals(0x3158959, version.hash);
        assertEquals(26, version.year);
        assertEquals(8, version.month);
        assertEquals(31, version.day);
        assertEquals("FastAsyncWorldEdit-2.15.5-folia.4", version.toString());
    }

    @Test
    void parsesUpstreamSnapshotVersion() {
        FaweVersion version = FaweVersion.tryParse("version=2.15.5-SNAPSHOT-123", "commit=-ff6cf80", "date=26.08.31");

        assertArrayEquals(new int[]{2, 15, 5}, version.semver);
        assertEquals(123, version.build);
        assertTrue(version.snapshot);
    }

    @Test
    void parsesPlainReleaseVersion() {
        FaweVersion version = FaweVersion.tryParse("version=2.15.5", "commit=-ff6cf80", "date=26.08.31");

        assertArrayEquals(new int[]{2, 15, 5}, version.semver);
        assertEquals(0, version.build);
        assertFalse(version.snapshot);
    }

    @Test
    void detectsNewerForkBuild() {
        FaweVersion installed = FaweVersion.tryParse("version=2.15.5-folia.4", "commit=-3158959", "date=26.08.31");
        FaweVersion latest = FaweVersion.tryParse("version=2.15.5-folia.5", "commit=-3158959", "date=26.08.31");
        FaweVersion same = FaweVersion.tryParse("version=2.15.5-folia.4", "commit=-3158959", "date=26.08.31");

        assertTrue(installed.isNewer(latest));
        assertFalse(installed.isNewer(same));
    }
}
