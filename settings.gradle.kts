pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PassengerRemote"

// Wire model shared by every role. Pure data + a hand-rolled JSON codec, no
// Android framework use beyond org.json, so it can be unit-tested on the JVM.
include(":protocol")

// Transport abstraction. The point of this module is that the remote UI never
// knows whether it is talking to another phone or to the host service running
// in its own process (see LoopbackTransport / self-remote mode).
include(":transport")

// Everything privileged: notification listener, media sessions, volume, nav
// intents, and the foreground service that owns them.
include(":host")

// The remote control UI itself, driven purely by protocol messages off a
// Transport. Shared verbatim between the passenger's phone and self-remote.
include(":remote")

// Single APK that can be host, remote, or both at once.
include(":app")
