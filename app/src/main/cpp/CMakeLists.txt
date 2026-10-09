# SPDX-License-Identifier: MPL-2.0
#
# Phase 3E-A native dependency foundation. This builds ONE ShelfOS-owned JNI
# shared library (shelfos_cbr) that statically links the vendored libarchive
# sources for future (3E-B+) read-only CBR/RAR support. No archive-reading
# logic lives here yet; see docs/adr/0024-native-cbr-libarchive.md.
cmake_minimum_required(VERSION 3.22)
project(shelfos_cbr C CXX)

# --- Vendored libarchive (third_party/libarchive), configured minimally ---
# Disable every optional system dependency, CLI front-end and test target.
# Libarchive's broader static-library source set is compiled and linked into
# the ShelfOS JNI library; link-time dead stripping keeps packaging bounded,
# and ShelfOS exposes only its small capability surface (no generic archive
# API, libarchive.so, or bsdtar/bsdcpio/bsdcat executables).
set(ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(ENABLE_TEST OFF CACHE BOOL "" FORCE)
set(ENABLE_COVERAGE OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ENABLE_TAR OFF CACHE BOOL "" FORCE)
set(ENABLE_CPIO OFF CACHE BOOL "" FORCE)
set(ENABLE_CAT OFF CACHE BOOL "" FORCE)
set(ENABLE_UNZIP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBB2 OFF CACHE BOOL "" FORCE)
set(ENABLE_LZ4 OFF CACHE BOOL "" FORCE)
set(ENABLE_LZO OFF CACHE BOOL "" FORCE)
set(ENABLE_LZMA OFF CACHE BOOL "" FORCE)
set(ENABLE_ZSTD OFF CACHE BOOL "" FORCE)
set(ENABLE_ZLIB OFF CACHE BOOL "" FORCE)
set(ENABLE_BZip2 OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBXML2 OFF CACHE BOOL "" FORCE)
set(ENABLE_EXPAT OFF CACHE BOOL "" FORCE)
set(ENABLE_WIN32_XMLLITE OFF CACHE BOOL "" FORCE)
set(ENABLE_PCREPOSIX OFF CACHE BOOL "" FORCE)
set(ENABLE_PCRE2POSIX OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBGCC OFF CACHE BOOL "" FORCE)
set(ENABLE_CNG OFF CACHE BOOL "" FORCE)
set(ENABLE_XATTR OFF CACHE BOOL "" FORCE)
set(ENABLE_ACL OFF CACHE BOOL "" FORCE)
set(ENABLE_ICONV OFF CACHE BOOL "" FORCE)
set(ENABLE_MBEDTLS OFF CACHE BOOL "" FORCE)
set(ENABLE_NETTLE OFF CACHE BOOL "" FORCE)
set(ENABLE_CLANG_TIDY OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libarchive"
    "${CMAKE_CURRENT_BINARY_DIR}/libarchive-build"
)

# --- ShelfOS JNI bridge ---
# shelfos_cbr_jni.cpp: Phase 3E-A minimal smoke-test surface (backendVersion()
# + a capability probe only). Unmodified by 3E-B; see
# docs/adr/0024-native-cbr-libarchive.md for scope.
# shelfos_rar_session_jni.cpp: Phase 3E-B generic internal RAR engine (real
# open/enumerate/extract session). See its header comment for the design
# contract (FD ownership, handle model, threading, error categories).
add_library(shelfos_cbr SHARED
    shelfos_cbr_jni.cpp
    shelfos_rar_session_jni.cpp
)

target_link_libraries(shelfos_cbr
    PRIVATE archive_static
    PRIVATE log
)

set_target_properties(shelfos_cbr PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
