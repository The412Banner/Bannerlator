# ----------------------------------------------------------------------------
# bl-steam-host — the app's genuine Steam session host (launchMode=AppSteam).
#
# A standalone arm64 executable, NOT a JNI library: it loads Valve's androidarm64
# libsteamclient.so (fetched at runtime from Valve's client CDN by SteamHostComponent;
# never bundled) in its OWN process so a crash inside the closed client can never take
# the app down, and serves the Steam3Master / SteamClientService loopback listeners the
# Wine game's lsteamclient bridge talks to. The app runs it from nativeLibraryDir like
# libinnoextract.so / libunarc.so — hence the lib*.so name: that is how an executable
# rides inside the APK's jniLibs (same trick as the proot build in this tree).
#
# GPL-3.0-or-later, modelled on WinNative's wn-steam-bootstrap — see NOTICE.md.
# Links only log + dl; C++17, no third-party dependencies.
# ----------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)

project(BlSteamHost CXX)

add_executable(libblsteamhost.so
        main.cpp
)

target_include_directories(libblsteamhost.so PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_features(libblsteamhost.so PRIVATE cxx_std_17)

target_compile_options(libblsteamhost.so PRIVATE
        -Wall -Wextra
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -fPIE
)

target_link_options(libblsteamhost.so PRIVATE
        -fPIE -pie
        -Wl,--gc-sections
        -Wl,-z,now
        "$<$<NOT:$<CONFIG:Debug>>:-Wl,--strip-all>"
)

target_link_libraries(libblsteamhost.so PRIVATE log dl)
