# libarchive's RAR and RAR5 readers, built as the static library archive_rar.
# Vendored at third_party/libarchive (see its README for why libarchive and
# not unRAR). Included by the app's CMakeLists.txt and by archive/tests.
set(LIBARCHIVE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/libarchive)
add_library(archive_rar STATIC
    ${LIBARCHIVE_DIR}/libarchive/archive_acl.c
    ${LIBARCHIVE_DIR}/libarchive/archive_blake2s_ref.c
    ${LIBARCHIVE_DIR}/libarchive/archive_blake2sp_ref.c
    ${LIBARCHIVE_DIR}/libarchive/archive_check_magic.c
    ${LIBARCHIVE_DIR}/libarchive/archive_entry.c
    ${LIBARCHIVE_DIR}/libarchive/archive_entry_sparse.c
    ${LIBARCHIVE_DIR}/libarchive/archive_entry_xattr.c
    ${LIBARCHIVE_DIR}/libarchive/archive_ppmd7.c
    ${LIBARCHIVE_DIR}/libarchive/archive_read.c
    ${LIBARCHIVE_DIR}/libarchive/archive_read_open_filename.c
    ${LIBARCHIVE_DIR}/libarchive/archive_read_support_format_rar.c
    ${LIBARCHIVE_DIR}/libarchive/archive_read_support_format_rar5.c
    ${LIBARCHIVE_DIR}/libarchive/archive_string.c
    ${LIBARCHIVE_DIR}/libarchive/archive_string_sprintf.c
    ${LIBARCHIVE_DIR}/libarchive/archive_time.c
    ${LIBARCHIVE_DIR}/libarchive/archive_util.c
    ${LIBARCHIVE_DIR}/libarchive/archive_virtual.c
)
target_include_directories(archive_rar
    PUBLIC ${LIBARCHIVE_DIR}/libarchive
    # archive.h includes android_lf.h on Android (libarchive's contrib/android/include).
    PUBLIC ${LIBARCHIVE_DIR}/include
    PRIVATE ${LIBARCHIVE_DIR}/config
)
target_compile_definitions(archive_rar PRIVATE PLATFORM_CONFIG_H="config.h")
# Upstream's code, kept as released: its warnings are not ours to fix here.
target_compile_options(archive_rar PRIVATE -O2 -w)
set_target_properties(archive_rar PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(archive_rar z)
