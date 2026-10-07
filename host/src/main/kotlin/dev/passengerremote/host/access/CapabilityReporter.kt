package dev.passengerremote.host.access

import dev.passengerremote.host.control.VolumeController
import dev.passengerremote.host.media.HostSnapshot
import dev.passengerremote.protocol.AccessTier
import dev.passengerremote.protocol.CapabilityReport
import dev.passengerremote.protocol.FeatureId
import dev.passengerremote.protocol.FeatureLevel
import dev.passengerremote.protocol.FeatureStatus

/**
 * Answers "what can this app actually do, here, right now?"
 *
 * Deliberately reports against live state rather than a static table: whether
 * the queue works depends on which app is playing, and whether the volume slider
 * works depends on the car. A fixed list would be a marketing claim; this is a
 * measurement.
 *
 * The other half of its job is saying what extra privilege does NOT fix. Volume
 * and head-unit app switching are unchanged by Shizuku or root, and quietly
 * leaving them off the "grant more access" list would be misleading.
 */
object CapabilityReporter {

    fun report(
        tier: AccessTier,
        notificationAccess: Boolean,
        snapshot: HostSnapshot,
        volumeFixed: Boolean,
    ): CapabilityReport {
        val playing = snapshot as? HostSnapshot.Playing
        val caps = playing?.nowPlaying?.caps
        val source = playing?.nowPlaying?.sourceLabel ?: "the playing app"

        val features = buildList {
            if (!notificationAccess) {
                add(
                    FeatureStatus(
                        FeatureId.MEDIA_TRANSPORT,
                        FeatureLevel.BLOCKED,
                        "Needs notification access. Nothing else works until this is granted.",
                    ),
                )
                add(
                    FeatureStatus(
                        FeatureId.NOW_PLAYING,
                        FeatureLevel.BLOCKED,
                        "Needs notification access.",
                    ),
                )
            } else {
                add(
                    FeatureStatus(
                        FeatureId.MEDIA_TRANSPORT,
                        if (playing == null) FeatureLevel.LIMITED else FeatureLevel.WORKS,
                        if (playing == null) {
                            "Ready. Start something playing and the controls come alive."
                        } else {
                            "Play, pause and skip work with $source."
                        },
                    ),
                )
                add(
                    FeatureStatus(
                        FeatureId.NOW_PLAYING,
                        if (playing == null) FeatureLevel.LIMITED else FeatureLevel.WORKS,
                        if (playing == null) "Nothing is playing." else "Reading title and artist from $source.",
                    ),
                )
            }

            add(
                when {
                    !notificationAccess -> FeatureStatus(
                        FeatureId.ARTWORK,
                        FeatureLevel.BLOCKED,
                        "Needs notification access.",
                    )
                    playing == null -> FeatureStatus(
                        FeatureId.ARTWORK,
                        FeatureLevel.LIMITED,
                        "Nothing is playing.",
                    )
                    playing.art == null -> FeatureStatus(
                        FeatureId.ARTWORK,
                        FeatureLevel.LIMITED,
                        "$source isn't publishing album art for this track.",
                    )
                    else -> FeatureStatus(FeatureId.ARTWORK, FeatureLevel.WORKS, "Album art is coming through.")
                },
            )

            add(
                FeatureStatus(
                    FeatureId.SEEK,
                    if (caps?.canSeek == true) FeatureLevel.WORKS else FeatureLevel.LIMITED,
                    if (caps?.canSeek == true) {
                        "You can scrub through a track."
                    } else {
                        "$source doesn't allow seeking from outside the app."
                    },
                ),
            )

            add(
                FeatureStatus(
                    FeatureId.QUEUE,
                    if (caps?.hasQueue == true) FeatureLevel.WORKS else FeatureLevel.LIMITED,
                    if (caps?.hasQueue == true) {
                        "$source publishes its queue, so you can jump around it."
                    } else {
                        "$source doesn't publish its queue. Most streaming apps don't — " +
                            "Spotify and YouTube Music expose only the current track. The " +
                            "remote shows what's playing instead."
                    },
                    // Explicitly no improvedBy: the queue is absent from the media
                    // session itself, so no privilege on this phone can conjure it.
                ),
            )

            add(
                FeatureStatus(
                    FeatureId.VOLUME,
                    if (volumeFixed) FeatureLevel.BLOCKED else FeatureLevel.WORKS,
                    if (volumeFixed) {
                        VolumeController.FIXED_VOLUME_DISCLAIMER
                    } else {
                        "The volume slider controls this phone's media volume."
                    },
                    // No improvedBy on purpose. A head unit that runs its own
                    // amplifier ignores the phone's volume at every privilege level;
                    // offering Shizuku here would be selling a fix that isn't one.
                ),
            )

            add(
                FeatureStatus(
                    FeatureId.DESTINATION,
                    FeatureLevel.WORKS,
                    "Sending a destination starts navigation, and brings the map up on " +
                        "the car screen when Android Auto is connected.",
                ),
            )

            add(
                if (tier == AccessTier.ROOTLESS) {
                    FeatureStatus(
                        FeatureId.VOICE,
                        FeatureLevel.LIMITED,
                        "The voice button opens the assistant on the phone. It may show " +
                            "the listening bar without actually starting to listen \u2014 that's " +
                            "a limitation of what a normal app is allowed to send.",
                        improvedBy = AccessTier.SHELL,
                        improvementDetail = "With Shizuku, the app sends the real " +
                            "voice-assist key instead of an intent the system can ignore, " +
                            "which is more likely to actually start listening. It still " +
                            "opens the assistant on your phone, not on the car screen \u2014 " +
                            "nothing can put the mic on the car screen.",
                    )
                } else {
                    FeatureStatus(
                        FeatureId.VOICE,
                        FeatureLevel.WORKS,
                        "Sending the real voice-assist key, the same one the " +
                            "steering-wheel button uses. Note this opens the assistant " +
                            "on your phone; Android Auto's own mic UI is not reachable " +
                            "by any app, at any privilege level.",
                    )
                },
            )

            add(
                FeatureStatus(
                    FeatureId.HEAD_UNIT_APP_SWITCH,
                    FeatureLevel.BLOCKED,
                    "Android Auto has no way for an app to switch which app is on the " +
                        "car screen. Sending a destination is the one exception, because " +
                        "navigation gets foregrounded as a side effect.",
                    // No improvedBy. Root doesn't add an API that doesn't exist; the
                    // only routes are the unofficial AA SDK or screen-mirroring hacks,
                    // and this app deliberately does neither.
                ),
            )
        }

        return CapabilityReport(tier, features)
    }
}
