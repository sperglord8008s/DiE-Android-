// SPDX-License-Identifier: GPL-3.0-only
/* Exercise the exact production callback/serializer code at hostile boundaries. */
#include "../media-engine/probe.c"
#include <assert.h>
#include <pthread.h>

static void typed_values(void) {
    char *keys[] = {"duplicate", "duplicate", "signed-zero", "nan", "unicode", "bytes"};
    char unicode[] = {'F',(char)0xf0,(char)0x9f,(char)0x98,(char)0x80,(char)0xc0,(char)0xaf,0};
    unsigned char raw[] = {0,255,127};
    mpv_byte_array bytes = {.data=raw,.size=sizeof(raw)};
    double nan; uint64_t nan_bits=UINT64_C(0x7ff8000000000042); memcpy(&nan,&nan_bits,sizeof(nan));
    mpv_node values[] = {
        {.format=MPV_FORMAT_INT64,.u.int64=INT64_MIN},
        {.format=MPV_FORMAT_FLAG,.u.flag=0},
        {.format=MPV_FORMAT_DOUBLE,.u.double_=-0.0},
        {.format=MPV_FORMAT_DOUBLE,.u.double_=nan},
        {.format=MPV_FORMAT_STRING,.u.string=unicode},
        {.format=MPV_FORMAT_BYTE_ARRAY,.u.ba=&bytes}
    };
    mpv_node_list list={.num=6,.values=values,.keys=keys};
    mpv_node root={.format=MPV_FORMAT_NODE_MAP,.u.list=&list};
    Json output={0}; node(&output,&root,0);
    assert(!output.error && output.nodes==7);
    const char *first=strstr(output.data,"\"key\":\"duplicate\"");
    assert(first && strstr(first+1,"\"key\":\"duplicate\""));
    assert(strstr(output.data,"\"decimal\":\"-9223372036854775808\""));
    assert(strstr(output.data,"\"ieee754Bits\":\"8000000000000000\""));
    assert(strstr(output.data,"\"value\":null,\"ieee754Bits\":\"7ff8000000000042\""));
    assert(strstr(output.data,"F\\ud83d\\ude00\\ufffd\\ufffd"));
    assert(strstr(output.data,"\"utf8Hex\":\"46f09f9880c0af\""));
    assert(strstr(output.data,"\"dataHex\":\"00ff7f\""));
    free(output.data);
    // A cyclic/deep engine node must fail before recursion exhausts the stack.
    list=(mpv_node_list){.num=1,.values=&root}; root=(mpv_node){.format=MPV_FORMAT_NODE_ARRAY,.u.list=&list};
    output=(Json){0}; node(&output,&root,0); assert(output.error); free(output.data);
    output=(Json){0}; append(&output,"",OUTPUT_LIMIT+1U); assert(output.error && !output.data);
}

typedef struct { Stream *stream; uint64_t read; } Reader;
static void *read_until_budget(void *argument) {
    Reader *reader=argument;
    char *buffer=malloc(1024*1024); assert(buffer);
    int64_t count;
    while ((count=stream_read(reader->stream,buffer,1024*1024))>0) reader->read+=(uint64_t)count;
    free(buffer); return NULL;
}
static void callbacks(void) {
    FILE *file=tmpfile(); assert(file);
    assert(ftruncate(fileno(file),(off_t)READ_LIMIT+8)==0 && fseek(file,13,SEEK_SET)==0);
    Input input={.fd=fileno(file),.size=READ_LIMIT+8,.deadline=now_ms()+DEADLINE_MS};
    atomic_init(&input.bytes,0); atomic_init(&input.seeks,0); atomic_init(&input.opens,0);
    atomic_init(&input.denied,0); atomic_init(&input.budget_hit,false);
    mpv_stream_cb_info first={0},second={0};
    assert(stream_open(&input,INPUT_URI,&first)==0 && stream_open(&input,INPUT_URI,&second)==0);
    assert(stream_size(first.cookie)==input.size && stream_seek(first.cookie,-1)<0 && stream_seek(first.cookie,input.size+1)<0);
    Reader a={.stream=first.cookie},b={.stream=second.cookie};
    pthread_t one,two;
    assert(pthread_create(&one,NULL,read_until_budget,&a)==0);
    assert(pthread_create(&two,NULL,read_until_budget,&b)==0);
    assert(pthread_join(one,NULL)==0 && pthread_join(two,NULL)==0);
    assert(a.read+b.read==READ_LIMIT && atomic_load(&input.bytes)==READ_LIMIT && atomic_load(&input.budget_hit));
    assert(ftell(file)==13); // pread/virtual seeks never reposition caller input.
    char byte;
    assert(stream_seek(first.cookie,input.size)==input.size && stream_read(first.cookie,&byte,1)==0);
    stream_cancel(first.cookie); assert(stream_read(first.cookie,&byte,1)<0 && stream_seek(first.cookie,0)<0);
    mpv_stream_cb_info denied={0}; assert(stream_open(&input,"dieblob://ungranted",&denied)<0 && atomic_load(&input.denied)==1);
    input.deadline=now_ms()-1;
    assert(stream_read(second.cookie,&byte,1)<0 && stream_open(&input,INPUT_URI,&denied)<0);
    stream_close(first.cookie); stream_close(second.cookie); fclose(file);
}
int main(void) {
    typed_values(); callbacks();
    puts("MPV C boundary fixtures passed: duplicate map keys, int64 limits, IEEE signed zero/NaN, invalid UTF-8/raw bytes, recursion/output caps, concurrent read-budget enforcement, descriptor position, cancellation, deadlines and ungranted URIs.");
    return 0;
}
