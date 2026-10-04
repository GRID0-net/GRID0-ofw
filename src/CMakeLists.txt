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

add_executable(GRID0-ofw-cli ${src})

target_compile_options(
    GRID0-ofw-cli
    PRIVATE "-DLANPLAY_VERSION=\"${LANPLAY_VERSION}\""
)
if (${CMAKE_CXX_COMPILER_ID} STREQUAL GNU)
    target_compile_options(
        GRID0-ofw-cli
        PRIVATE "-Wall"
        PRIVATE "-Wno-unused-function"
    )
endif()

set_target_properties(GRID0-ofw-cli PROPERTIES CXX_STANDARD 17)

if(NOT ((CMAKE_SYSTEM_NAME STREQUAL "Darwin") OR MSVC))
    target_link_libraries(GRID0-ofw-cli atomic)
endif()
target_link_libraries(GRID0-ofw-cli base)
target_link_libraries(GRID0-ofw-cli uv_lwip)
target_link_libraries(GRID0-ofw-cli uv_a)

find_package(PCAP REQUIRED)
if(NOT WIN32)
    target_link_libraries(GRID0-ofw-cli ${PCAP_LIBRARY})
endif()
target_include_directories(GRID0-ofw-cli PRIVATE ${PCAP_INCLUDE_DIR})

if (WIN32)
    target_sources(GRID0-ofw-cli PRIVATE npcap-loader.c win-firewall.c)
    target_link_libraries(GRID0-ofw-cli ws2_32 iphlpapi psapi userenv shell32)
    target_compile_definitions(GRID0-ofw-cli PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
else()
    if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
        target_link_libraries(GRID0-ofw-cli pthread)
    endif()
endif()

INSTALL(TARGETS GRID0-ofw-cli RUNTIME DESTINATION bin)
