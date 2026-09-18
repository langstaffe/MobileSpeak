set(ANDROID_ABI "$ENV{TS_ANDROID_ABI}" CACHE STRING "Target ABI")
set(ANDROID_PLATFORM android-24 CACHE STRING "Minimum Android API")
include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
