set shell := ["bash", "-euo", "pipefail", "-c"]

default:
    @just --list

# Formatting check, Android lint, JVM tests, and a debug build.
check:
    ./gradlew --no-daemon ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug

# Apply Kotlin formatting.
fmt:
    ./gradlew --no-daemon ktlintFormat

fmt-check:
    ./gradlew --no-daemon ktlintCheck

lint:
    ./gradlew --no-daemon :app:lintDebug

test:
    ./gradlew --no-daemon :app:testDebugUnitTest

# Talk to the real robot from the JVM using the python-roborock cache at ~/.roborock.
live-test:
    ROBOROCK_LIVE=1 ./gradlew --no-daemon :app:testDebugUnitTest --tests '*LiveRobotTest*' --rerun -i

build:
    ./gradlew --no-daemon :app:assembleDebug

# Install the debug APK; pass the adb serial when several devices are attached.
install serial="":
    adb {{ if serial == "" { "" } else { "-s " + serial } }} install -r app/build/outputs/apk/debug/app-debug.apk
