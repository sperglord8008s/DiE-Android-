/* SPDX-License-Identifier: GPL-3.0-only */
#include <lzma.h>
#include <zstd.h>
#include <stdint.h>

/* Linker wrappers bound decoder dictionaries without modifying upstream source. */
#define DIE_ARCHIVE_DICTIONARY_BYTES (UINT64_C(64) * 1024 * 1024)
static uint64_t limit(uint64_t requested) {
    return requested < DIE_ARCHIVE_DICTIONARY_BYTES ? requested : DIE_ARCHIVE_DICTIONARY_BYTES;
}
extern lzma_ret __real_lzma_stream_decoder(lzma_stream *, uint64_t, uint32_t);
extern lzma_ret __real_lzma_auto_decoder(lzma_stream *, uint64_t, uint32_t);
extern lzma_ret __real_lzma_alone_decoder(lzma_stream *, uint64_t);
extern lzma_ret __real_lzma_lzip_decoder(lzma_stream *, uint64_t, uint32_t);
extern lzma_ret __real_lzma_raw_decoder(lzma_stream *, const lzma_filter *);

lzma_ret __wrap_lzma_stream_decoder(lzma_stream *stream, uint64_t memory, uint32_t flags) {
    return __real_lzma_stream_decoder(stream, limit(memory), flags);
}
lzma_ret __wrap_lzma_auto_decoder(lzma_stream *stream, uint64_t memory, uint32_t flags) {
    return __real_lzma_auto_decoder(stream, limit(memory), flags);
}
lzma_ret __wrap_lzma_alone_decoder(lzma_stream *stream, uint64_t memory) {
    return __real_lzma_alone_decoder(stream, limit(memory));
}
lzma_ret __wrap_lzma_lzip_decoder(lzma_stream *stream, uint64_t memory, uint32_t flags) {
    return __real_lzma_lzip_decoder(stream, limit(memory), flags);
}
lzma_ret __wrap_lzma_raw_decoder(lzma_stream *stream, const lzma_filter *filters) {
    uint64_t usage = lzma_raw_decoder_memusage(filters);
    if (usage == UINT64_MAX) return LZMA_OPTIONS_ERROR;
    if (usage > DIE_ARCHIVE_DICTIONARY_BYTES) return LZMA_MEMLIMIT_ERROR;
    return __real_lzma_raw_decoder(stream, filters);
}
extern ZSTD_DStream *__real_ZSTD_createDStream(void);
ZSTD_DStream *__wrap_ZSTD_createDStream(void) {
    ZSTD_DStream *stream = __real_ZSTD_createDStream();
    if (stream && ZSTD_isError(ZSTD_DCtx_setParameter(stream, ZSTD_d_windowLogMax, 26))) {
        ZSTD_freeDStream(stream); return NULL;
    }
    return stream;
}
