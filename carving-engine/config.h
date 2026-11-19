/* SPDX-License-Identifier: GPL-3.0-only
 * Fixed POSIX/API-26 capabilities for the read-only PhotoRec format adapter.
 * Optional libjpeg/zlib/filesystem libraries are deliberately not enabled.
 */
#define _GNU_SOURCE 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRCASESTR 1
#define HAVE_TIME_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_FTELLO 1
#define HAVE_FSEEKO 1
#define HAVE_CTYPE_H 1
#define HAVE_STDARG_H 1
#define HAVE_ERRNO_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_FCNTL_H 1
#define HAVE_SETJMP_H 1
#define HAVE_STRINGS_H 1
#define HAVE_LOCALTIME_R 1
#define HAVE_GMTIME_R 1
#define HAVE_SNPRINTF 1
#define HAVE_VSNPRINTF 1
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define TESTDISK_LSB 1
#elif __BYTE_ORDER__ != __ORDER_BIG_ENDIAN__
#error Unsupported native byte order
#endif
#define VERSION "7.2"
#define PACKAGE_VERSION "7.2"
#define TESTDISKDATE "February 2024"
