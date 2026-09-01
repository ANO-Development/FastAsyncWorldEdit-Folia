package com.fastasyncworldedit.core.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweVersion;
import com.fastasyncworldedit.core.configuration.Caption;
import com.fastasyncworldedit.core.configuration.Settings;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.event.ClickEvent;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.VisibleForTesting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateNotification {

    private static final String GITHUB_LAST_RELEASE = "https://api.github.com/repos/ANO-Development/FastAsyncWorldEdit-Folia/releases/latest";

    private static final String LINK_DOWNLOAD_RELEASES = "https://github.com/ANO-Development/FastAsyncWorldEdit-Folia/releases";

    private static final String CONSOLE_NOTIFICATION_OUTDATED_RELEASE = """
            A new release for FastAsyncWorldEdit is available: {}. You are currently on {}.
            Download from {}""";

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Pattern GITHUB_RESPONSE_TAG_NAME_PATTERN = Pattern.compile(
            "\"tag_name\":\"(\\d+\\.\\d+\\.\\d+)(?:-folia\\.(\\d+))?\"");

    private static volatile int[] lastRelease;
    private static volatile String lastReleaseTag;

    /**
     * Check whether a newer release of the Folia fork is published on GitHub.
     */
    public static void doUpdateCheck() {
        if (hasUpdateInfo()) {
            return;
        }
        final FaweVersion installedVersion = Fawe.instance().getVersion();
        if (installedVersion == null || installedVersion.semver == null) {
            return;
        }
        if (Settings.settings().ENABLED_COMPONENTS.RELEASE_UPDATE_NOTIFICATIONS) {
            checkLatestRelease().orTimeout(10, TimeUnit.SECONDS).whenComplete((version, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Failed to check for latest release", throwable);
                    return;
                }
                lastRelease = version;
                if (hasUpdate(installedVersion.semver, installedVersion.build, version)) {
                    LOGGER.warn(CONSOLE_NOTIFICATION_OUTDATED_RELEASE,
                            lastReleaseTag,
                            installedVersion.toString(),
                            LINK_DOWNLOAD_RELEASES
                    );
                }
            });
        }
    }

    private static CompletableFuture<int[]> checkLatestRelease() {
        return HTTP_CLIENT.sendAsync(
                HttpRequest.newBuilder().GET().uri(URI.create(GITHUB_LAST_RELEASE)).build(),
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new UpdateCheckException("GitHub returned status code " + response.statusCode());
            }
            return response.body();
        }).thenApply(body -> {
            final Matcher matcher = GITHUB_RESPONSE_TAG_NAME_PATTERN.matcher(body);
            if (!matcher.find()) {
                throw new UpdateCheckException("Couldn't find tag name in response");
            }
            try {
                lastReleaseTag = matcher.group(1) + (matcher.group(2) == null ? "" : "-folia." + matcher.group(2));
                return parseReleaseTag(matcher.group(1), matcher.group(2));
            } catch (NumberFormatException e) {
                throw new UpdateCheckException("Couldn't parse release tag", e);
            }
        });
    }

    /**
     * Trigger an update notification based on captions. Useful to notify server administrators ingame.
     *
     * @param actor The player to notify.
     */
    public static void doUpdateNotification(Actor actor) {
        if (!isAnyUpdateCheckEnabled() || !actor.hasPermission("fawe.admin") || !hasUpdateInfo()) {
            return;
        }
        final FaweVersion installed = Fawe.instance().getVersion();
        if (installed == null) {
            return;
        }
        if (installed.semver != null && lastRelease != null && Settings.settings().ENABLED_COMPONENTS.RELEASE_UPDATE_NOTIFICATIONS) {
            if (hasUpdate(installed.semver, installed.build, lastRelease)) {
                actor.print(Caption.of(
                        "fawe.info.update-available.release",
                        lastReleaseTag,
                        installed.toString(),
                        TextComponent.of("GitHub releases").clickEvent(ClickEvent.openUrl(LINK_DOWNLOAD_RELEASES))
                ));
            }
        }
    }

    @VisibleForTesting
    static boolean hasUpdateSemver(int[] installed, int[] latest) {
        for (int i = 0; i < Math.max(installed.length, latest.length); i++) {
            final int installedPart = i < installed.length ? installed[i] : 0;
            final int latestPart = i < latest.length ? latest[i] : 0;
            if (installedPart != latestPart) {
                return installedPart < latestPart;
            }
        }
        return false;
    }

    @VisibleForTesting
    static int[] parseReleaseTag(String semver, String forkBuild) {
        int[] version = Arrays.stream(semver.split("\\.")).mapToInt(Integer::parseInt).toArray();
        return new int[]{version[0], version[1], version[2], forkBuild == null ? 0 : Integer.parseInt(forkBuild)};
    }

    @VisibleForTesting
    static boolean hasUpdate(int[] installedSemver, int installedBuild, int[] latestRelease) {
        int[] latestSemver = Arrays.copyOfRange(latestRelease, 0, 3);
        if (hasUpdateSemver(installedSemver, latestSemver)) {
            return true;
        }
        return Arrays.equals(installedSemver, latestSemver) && latestRelease[3] > installedBuild;
    }

    private static boolean hasUpdateInfo() {
        return lastRelease != null;
    }

    private static boolean isAnyUpdateCheckEnabled() {
        return Settings.settings().ENABLED_COMPONENTS.RELEASE_UPDATE_NOTIFICATIONS;
    }

    private static final class UpdateCheckException extends RuntimeException {

        public UpdateCheckException(final String message) {
            super("Failed to check for update: " + message);
        }

        public UpdateCheckException(final String message, final Throwable cause) {
            super("Failed to check for update: " + message, cause);
        }

    }

}
