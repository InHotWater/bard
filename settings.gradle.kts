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
        // Light's LP3 keyboard, published publicly through JitPack (as the Light SDK does).
        // The previous GitHub Packages coordinate needed a read:packages token to build.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.lightphone") }
        }
    }
}

rootProject.name = "libbylight"
include(":app")
