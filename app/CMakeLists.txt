cmake_minimum_required(VERSION 3.4.1)

# Which external-libs/<abi>/ directory to link against. Android sets ANDROID_ABI; a
# desktop build passes -D ANON_ABI=linux-x86_64.
if(NOT DEFINED ANON_ABI)
    set(ANON_ABI ${ANDROID_ABI})
endif()
message(STATUS ABI_INFO  = ${ANON_ABI})

add_library( anonero
             SHARED
             src/main/cpp/anonero.cpp )

set(EXTERNAL_LIBS_DIR ${CMAKE_SOURCE_DIR}/../external-libs)

############
# libsodium
############

add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libsodium.a)

############
# OpenSSL
############

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libssl.a)

############
# Boost
############

add_library(boost_chrono STATIC IMPORTED)
set_target_properties(boost_chrono PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_chrono.a)

add_library(boost_date_time STATIC IMPORTED)
set_target_properties(boost_date_time PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_date_time.a)

add_library(boost_filesystem STATIC IMPORTED)
set_target_properties(boost_filesystem PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_filesystem.a)

add_library(boost_program_options STATIC IMPORTED)
set_target_properties(boost_program_options PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_program_options.a)

add_library(boost_regex STATIC IMPORTED)
set_target_properties(boost_regex PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_regex.a)

add_library(boost_serialization STATIC IMPORTED)
set_target_properties(boost_serialization PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_serialization.a)

add_library(boost_system STATIC IMPORTED)
set_target_properties(boost_system PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_system.a)

add_library(boost_thread STATIC IMPORTED)
set_target_properties(boost_thread PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_thread.a)

add_library(boost_wserialization STATIC IMPORTED)
set_target_properties(boost_wserialization PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libboost_wserialization.a)

#############
# Monero
#############

add_library(wallet_api STATIC IMPORTED)
set_target_properties(wallet_api PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libwallet_api.a)

add_library(wallet STATIC IMPORTED)
set_target_properties(wallet PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libwallet.a)

add_library(polyseed STATIC IMPORTED)
set_target_properties(polyseed PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libpolyseed.a)

add_library(polyseed-wrapper STATIC IMPORTED)
set_target_properties(polyseed-wrapper PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libpolyseed_wrapper.a)

add_library(cryptonote_core STATIC IMPORTED)
set_target_properties(cryptonote_core PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcryptonote_core.a)

add_library(cryptonote_basic STATIC IMPORTED)
set_target_properties(cryptonote_basic PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcryptonote_basic.a)

add_library(mnemonics STATIC IMPORTED)
set_target_properties(mnemonics PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libmnemonics.a)

add_library(common STATIC IMPORTED)
set_target_properties(common PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcommon.a)

add_library(cncrypto STATIC IMPORTED)
set_target_properties(cncrypto PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcncrypto.a)

add_library(ringct STATIC IMPORTED)
set_target_properties(ringct PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libringct.a)

add_library(ringct_basic STATIC IMPORTED)
set_target_properties(ringct_basic PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libringct_basic.a)

add_library(blockchain_db STATIC IMPORTED)
set_target_properties(blockchain_db PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libblockchain_db.a)

add_library(lmdb STATIC IMPORTED)
set_target_properties(lmdb PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/liblmdb.a)

add_library(easylogging STATIC IMPORTED)
set_target_properties(easylogging PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libeasylogging.a)

add_library(unbound STATIC IMPORTED)
set_target_properties(unbound PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libunbound.a)

add_library(epee STATIC IMPORTED)
set_target_properties(epee PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libepee.a)

add_library(blocks STATIC IMPORTED)
set_target_properties(blocks PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libblocks.a)

add_library(checkpoints STATIC IMPORTED)
set_target_properties(checkpoints PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcheckpoints.a)

add_library(device STATIC IMPORTED)
set_target_properties(device PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libdevice.a)

add_library(device_trezor STATIC IMPORTED)
set_target_properties(device_trezor PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libdevice_trezor.a)

add_library(multisig STATIC IMPORTED)
set_target_properties(multisig PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libmultisig.a)

add_library(version STATIC IMPORTED)
set_target_properties(version PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libversion.a)

add_library(net STATIC IMPORTED)
set_target_properties(net PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libnet.a)

add_library(utf8proc STATIC IMPORTED)
set_target_properties(utf8proc PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libutf8proc.a)

add_library(hardforks STATIC IMPORTED)
set_target_properties(hardforks PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libhardforks.a)

add_library(randomx STATIC IMPORTED)
set_target_properties(randomx PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/librandomx.a)

add_library(rpc_base STATIC IMPORTED)
set_target_properties(rpc_base PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/librpc_base.a)

add_library(wallet-crypto STATIC IMPORTED)
set_target_properties(wallet-crypto PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libwallet-crypto.a)

add_library(cryptonote_format_utils_basic STATIC IMPORTED)
set_target_properties(cryptonote_format_utils_basic PROPERTIES IMPORTED_LOCATION
        ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/monero/libcryptonote_format_utils_basic.a)

#############
# System
#############

if(ANDROID)
    find_library( log-lib log )
    find_library( dl-lib dl )
    set(PLATFORM_LIBS ${log-lib} ${dl-lib})
    set(PLATFORM_LINK_OPTS "")
else()
    # jni.h comes from the JDK, not a sysroot; zlib comes from our prefix rather than
    # the platform (Android gets it from the NDK).

    # Only JNI_INCLUDE_DIRS is used -- no JVM library is linked (JNI calls go through
    # the JNIEnv table). Without COMPONENTS, FindJNI also demands AWT, which a headless
    # JDK doesn't ship.
    find_package(JNI REQUIRED COMPONENTS JVM)
    find_package(Threads REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})

    add_library(z STATIC IMPORTED)
    set_target_properties(z PROPERTIES IMPORTED_LOCATION
            ${EXTERNAL_LIBS_DIR}/${ANON_ABI}/libz.a)

    set(PLATFORM_LIBS z Threads::Threads ${CMAKE_DL_LIBS})

    # libwallet-crypto.a's ed25519 assembly has no .note.GNU-stack marker, so older
    # linkers mark the whole library as needing an executable stack -- which would apply
    # to the entire JVM process. It doesn't need one. x86-64 only, so Android never hit
    # this: libwallet-crypto is not linked on ARM.
    set(PLATFORM_LINK_OPTS "-Wl,-z,noexecstack")
endif()

include_directories( ${EXTERNAL_LIBS_DIR}/include )

message(STATUS EXTERNAL_LIBS_DIR :  ${EXTERNAL_LIBS_DIR})

if(ANON_ABI MATCHES "x86_64")
    set(EXTRA_LIBS "wallet-crypto")
else()
    set(EXTRA_LIBS "")
endif()

target_link_libraries(  anonero

                        wallet_api
                        wallet
                        cryptonote_core
                        cryptonote_basic
                        cryptonote_format_utils_basic
                        polyseed
                        polyseed-wrapper
                        utf8proc
                        mnemonics
                        ringct
                        ringct_basic
                        net
                        common
                        cncrypto
                        blockchain_db
                        lmdb
                        easylogging
                        unbound
                        epee
                        blocks
                        checkpoints
                        device
                        device_trezor
                        multisig
                        version
                        randomx
                        hardforks
                        rpc_base
                        ${EXTRA_LIBS}

                        boost_chrono
                        boost_date_time
                        boost_filesystem
                        boost_program_options
                        boost_regex
                        boost_serialization
                        boost_system
                        boost_thread
                        boost_wserialization

                        ssl
                        crypto

                        sodium

                       ${PLATFORM_LIBS}
                       )
target_link_options( anonero PRIVATE "-Wl,--exclude-libs,ALL" ${PLATFORM_LINK_OPTS} )
