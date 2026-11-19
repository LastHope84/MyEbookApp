cmake_minimum_required(VERSION 3.22.1)
project(mobiconv C)

set(LIBMOBI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/libmobi/src)

add_library(mobiconv SHARED
    mobi_jni.c
    ${LIBMOBI_SRC}/buffer.c
    ${LIBMOBI_SRC}/compression.c
    ${LIBMOBI_SRC}/debug.c
    ${LIBMOBI_SRC}/index.c
    ${LIBMOBI_SRC}/memory.c
    ${LIBMOBI_SRC}/meta.c
    ${LIBMOBI_SRC}/opf.c
    ${LIBMOBI_SRC}/parse_rawml.c
    ${LIBMOBI_SRC}/read.c
    ${LIBMOBI_SRC}/structure.c
    ${LIBMOBI_SRC}/util.c
    ${LIBMOBI_SRC}/write.c
    ${LIBMOBI_SRC}/xmlwriter.c
)

target_include_directories(mobiconv PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBMOBI_SRC}
)

target_compile_definitions(mobiconv PRIVATE
    HAVE_CONFIG_H=1
    USE_XMLWRITER=1
    HAVE_STRDUP=1
)

target_link_libraries(mobiconv z log)
