find_package(Qt6 6.4 REQUIRED COMPONENTS Widgets Network Test)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

# The updater compares this against release tags, so the GUI and its tests
# must see the real project version, not a hardcoded fallback.
add_compile_definitions("LANPLAY_VERSION=\"${LANPLAY_VERSION}\"")

add_library(zll_desktop_model STATIC model.cpp controller.cpp dependencies.cpp)
target_compile_features(zll_desktop_model PUBLIC cxx_std_17)
target_link_libraries(zll_desktop_model PUBLIC Qt6::Widgets Qt6::Network)
target_include_directories(zll_desktop_model PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

add_executable(GRID0-ofw WIN32 MACOSX_BUNDLE main.cpp window.cpp resources.qrc ../src/win-firewall.c)
target_include_directories(GRID0-ofw PRIVATE ../src)
target_link_libraries(GRID0-ofw PRIVATE zll_desktop_model)
set_target_properties(GRID0-ofw PROPERTIES
    MACOSX_BUNDLE_BUNDLE_NAME "GRID0-ofw"
    MACOSX_BUNDLE_GUI_IDENTIFIER "org.grid0.relay"
    MACOSX_BUNDLE_SHORT_VERSION_STRING "${PROJECT_VERSION}"
    MACOSX_BUNDLE_BUNDLE_VERSION "${PROJECT_VERSION}")
add_dependencies(GRID0-ofw GRID0-ofw-cli)
if(UNIX)
    # macOS and Linux both start the relay through this helper: one authorized
    # launch, one child, owned by the GUI's private socket.
    add_executable(zll-supervisor posix-supervisor.cpp)
    target_compile_features(zll-supervisor PRIVATE cxx_std_17)
    set_target_properties(zll-supervisor PROPERTIES OUTPUT_NAME GRID0-ofw-supervisor AUTOMOC OFF)
    if(ZLL_STATIC_RUNTIME)
        target_link_options(zll-supervisor PRIVATE -static-libstdc++ -static-libgcc)
    endif()
    add_dependencies(GRID0-ofw zll-supervisor)
    add_custom_command(TARGET GRID0-ofw POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:GRID0-ofw-cli>" "$<TARGET_FILE_DIR:GRID0-ofw>/GRID0-ofw-cli"
        COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:zll-supervisor>" "$<TARGET_FILE_DIR:GRID0-ofw>/GRID0-ofw-supervisor")
endif()
if(APPLE)
    # Native macOS 26 glass for a few contained surfaces. The library is a
    # no-op away from Cocoa; Windows and Linux retain their native Qt styling.
    add_subdirectory(${PROJECT_SOURCE_DIR}/external/qt-liquid-glass qt-liquid-glass)
    target_link_libraries(GRID0-ofw PRIVATE QtLiquidGlass)
    add_custom_command(TARGET GRID0-ofw POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_BUNDLE_DIR:GRID0-ofw>/Contents/Resources"
        COMMAND ${CMAKE_COMMAND} -E copy "${PROJECT_SOURCE_DIR}/LICENSE.txt" "$<TARGET_BUNDLE_DIR:GRID0-ofw>/Contents/Resources/LICENSE.txt")
endif()

if(WIN32)
    target_link_libraries(zll_desktop_model PRIVATE iphlpapi wintrust advapi32 winhttp)
    target_compile_definitions(zll_desktop_model PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
    # Build the entry point with the selected compiler's CRT. The static Qt
    # entry point from a downloaded SDK may use a different Windows CRT.
    set_target_properties(GRID0-ofw PROPERTIES QT_NO_ENTRYPOINT ON)
    target_sources(GRID0-ofw PRIVATE windows.rc windows-entry.cpp)
    if(MSVC)
        target_link_options(GRID0-ofw PRIVATE /MANIFEST:NO)
    endif()
    add_custom_command(TARGET GRID0-ofw POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:GRID0-ofw-cli>" "$<TARGET_FILE_DIR:GRID0-ofw>/GRID0-ofw-cli.exe")
endif()

enable_testing()
add_executable(zll-desktop-tests tests.cpp)
target_link_libraries(zll-desktop-tests PRIVATE zll_desktop_model Qt6::Test)
if(WIN32)
    add_executable(zll-test-relay windows-test-relay.cpp)
    set_target_properties(zll-test-relay PROPERTIES AUTOMOC OFF)
    add_dependencies(zll-desktop-tests zll-test-relay)
    # Exercise the same WinMain and UI code without UAC on a CI runner.
    add_executable(zll-startup-test WIN32 main.cpp window.cpp windows-entry.cpp)
    set_target_properties(zll-startup-test PROPERTIES QT_NO_ENTRYPOINT ON)
    target_link_libraries(zll-startup-test PRIVATE zll_desktop_model)
endif()
add_test(NAME desktop COMMAND zll-desktop-tests)

add_executable(zll-layout-tests layout-tests.cpp window.cpp)
target_link_libraries(zll-layout-tests PRIVATE zll_desktop_model Qt6::Test)
if(APPLE)
    target_link_libraries(zll-layout-tests PRIVATE QtLiquidGlass)
endif()
add_test(NAME desktop-layout COMMAND zll-layout-tests)
set_tests_properties(desktop-layout PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
