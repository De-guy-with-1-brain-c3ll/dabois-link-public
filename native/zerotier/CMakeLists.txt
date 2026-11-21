cmake_minimum_required(VERSION 3.25)
project(dabois_zerotier LANGUAGES C CXX)

set(LIBZT_SOURCE "" CACHE PATH "Checkout of libzt at a6f6886a8c8c07249f9e5d0cae431848727fe845")
if(NOT EXISTS "${LIBZT_SOURCE}/include/ZeroTierSockets.h" OR
   NOT EXISTS "${LIBZT_SOURCE}/ext/ZeroTierOne/node/Node.hpp" OR
   NOT EXISTS "${LIBZT_SOURCE}/ext/lwip/src/include/lwip/tcp.h")
    message(FATAL_ERROR "Point LIBZT_SOURCE to the pinned libzt checkout and initialize its submodules.")
endif()

find_package(JNI REQUIRED COMPONENTS JVM)
find_package(Git REQUIRED)
find_package(Threads REQUIRED)
set(minecraft_patch "${CMAKE_CURRENT_SOURCE_DIR}/minecraft-defaults.patch")
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${LIBZT_SOURCE}" apply --reverse --check "${minecraft_patch}"
    RESULT_VARIABLE already_patched OUTPUT_QUIET ERROR_QUIET)
if(NOT already_patched EQUAL 0)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${LIBZT_SOURCE}" apply "${minecraft_patch}"
        COMMAND_ERROR_IS_FATAL ANY)
endif()
set(BUILD_SHARED_LIBS OFF)
set(BUILD_STATIC_LIB OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(BUILD_HOST_SELFTEST OFF CACHE BOOL "" FORCE)
set(ALLOW_INSTALL_TARGET OFF CACHE BOOL "" FORCE)
set(ZTS_DISABLE_CENTRAL_API ON CACHE BOOL "" FORCE)
add_subdirectory("${LIBZT_SOURCE}" libzt EXCLUDE_FROM_ALL)
target_include_directories(zt_pic PRIVATE "${LIBZT_SOURCE}/ext/ZeroTierOne/ext")
target_include_directories(zto_pic PRIVATE "${LIBZT_SOURCE}/ext/ZeroTierOne/ext")

add_library(dabois_zerotier SHARED dabois_zerotier.c)
target_compile_features(dabois_zerotier PRIVATE c_std_11)
target_include_directories(dabois_zerotier PRIVATE ${JNI_INCLUDE_DIRS} "${LIBZT_SOURCE}/include")
set_target_properties(dabois_zerotier PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(dabois_zerotier PRIVATE
    zt_pic lwip_pic zto_pic natpmp_pic miniupnpc_pic Threads::Threads)
if(WIN32)
    target_compile_definitions(dabois_zerotier PRIVATE ADD_EXPORTS)
    target_link_libraries(dabois_zerotier PRIVATE ws2_32 shlwapi iphlpapi)
endif()
install(TARGETS dabois_zerotier RUNTIME DESTINATION . LIBRARY DESTINATION .)
