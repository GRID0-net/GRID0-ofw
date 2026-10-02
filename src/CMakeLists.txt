set(src
    main.c
    lan-play.c
    dhcp-server.c
    native-udp.c
    pcaploop.cpp
    packet.c
    arp.c
    helper.c
    lan-client.c
    gateway.cpp
    proxy_direct.cpp
    proxy_socks5.cpp
    sha1.c
    ipv4/ipv4.c
    ipv4/tcp.c
    ipv4/udp.c
    ipv4/icmp.c
    rpc/rpc.cpp
    rpc/lan-play.cpp
    rpc/rpc-server.cpp
    rpc/rpc-ws-server.cpp
)

add_executable(GRID0-ofw ${src})

target_compile_options(
    GRID0-ofw
    PRIVATE "-DLANPLAY_VERSION=\"${LANPLAY_VERSION}\""
)
if (${CMAKE_CXX_COMPILER_ID} STREQUAL GNU)
    target_compile_options(
        GRID0-ofw
        PRIVATE "-Wall"
        PRIVATE "-Wno-unused-function"
    )
endif()

set_target_properties(GRID0-ofw PROPERTIES CXX_STANDARD 17)

if(NOT ((CMAKE_SYSTEM_NAME STREQUAL "Darwin") OR MSVC))
    target_link_libraries(GRID0-ofw atomic)
endif()
target_link_libraries(GRID0-ofw base)
target_link_libraries(GRID0-ofw uv_lwip)
target_link_libraries(GRID0-ofw uv_a)

find_package(PCAP REQUIRED)
if(NOT WIN32)
    target_link_libraries(GRID0-ofw ${PCAP_LIBRARY})
endif()
target_include_directories(GRID0-ofw PRIVATE ${PCAP_INCLUDE_DIR})

if (WIN32)
    target_sources(GRID0-ofw PRIVATE npcap-loader.c win-firewall.c)
    target_link_libraries(GRID0-ofw ws2_32 iphlpapi psapi userenv shell32)
    target_compile_definitions(GRID0-ofw PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
else()
    if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
        target_link_libraries(GRID0-ofw pthread)
    endif()
endif()

INSTALL(TARGETS GRID0-ofw RUNTIME DESTINATION bin)
