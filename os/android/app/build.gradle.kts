// LibertyRecomp Android — :app module.

//

// Gradle never runs CMake. The native side (libmain.so, librexruntime.so,

// the GPU plugins, libSDL3.so, libc++_shared.so) is millions of lines of

// recompiled PowerPC and is built by os/android/scripts/build_native.sh,

// which stages the libraries into src/main/jniLibs and the title resources

// into src/main/assets/Resources. This module only packages them.

//

// The SDL Java classes come from the same SDL3 tree the native library is

// built from: SDLActivity refuses to start when the versions differ.

plugins {

    id("com.android.application")

}

val sdlJavaDir = rootProject.file(

    "../../glue/rexglue-sdk-main/thirdparty/sdl3/android-project/app/src/main/java")

// Release signing inputs. The key is never in the repository: point these at a
// local keystore (gradle.properties) or let CI supply the same values as
// environment variables. When no keystore is configured the release build is
// unsigned, which is enough to stage classes.dex/AndroidManifest.xml for
// build-apk.yml (apksigner signs the final APK there) but is not installable
// as an update over a signed release. See ../SIGNING.md.
val releaseStoreFile =
    ((findProperty("libertyReleaseKeystore") as String?)
        ?: System.getenv("ANDROID_KEYSTORE_PATH"))
        ?.let { file(it) }
        ?.takeIf { it.exists() }
val releaseStorePassword: String? = (findProperty("libertyReleaseStorePassword") as String?)
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = (findProperty("libertyReleaseKeyAlias") as String?)
    ?: System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = (findProperty("libertyReleaseKeyPassword") as String?)
    ?: System.getenv("ANDROID_KEY_PASSWORD")

android {

    namespace   = "com.libertyrecomp"

    compileSdk  = 35

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {

        applicationId = "com.libertyrecomp"

        // 28: ASharedMemory (26) for guest memory and AAudio (27) for audio.

        minSdk        = 29

        targetSdk     = 35

        versionCode   = 19

        versionName   = "0.5.9.4"

    }

    sourceSets["main"].java.srcDirs("src/main/java", sdlJavaDir)

    buildTypes {

        debug {

            isDebuggable    = true

            isJniDebuggable = true

            isMinifyEnabled = false

        }

        release {

            isMinifyEnabled = false

            // Never fall back to the debug key: an APK signed with it cannot be
            // installed over a release-signed one, and CI must sign explicitly.
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }

        }

    }

    compileOptions {

        sourceCompatibility = JavaVersion.VERSION_17

        targetCompatibility = JavaVersion.VERSION_17

    }

    packaging {

        jniLibs {

            // Unpack the libraries into nativeLibraryDir: the runtime finds its

            // GPU plugins by real path next to itself, and a Turnip driver

            // loaded through libadrenotools needs real files as well.

            useLegacyPackaging = true

            // build_native.sh decides what is stripped.

            keepDebugSymbols += "**/*.so"

        }

    }

    lint {

        checkReleaseBuilds = false

        abortOnError = false

    }

}

