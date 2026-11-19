/* SPDX-License-Identifier: GPL-3.0-only */
#include <archive.h>
#include <archive_entry.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

/* Direct unmodified upstream calls, without the application descriptor/JSON adapter. */
static long long position(const void *bytes, size_t size, int read_data) {
    struct archive *reader=archive_read_new();struct archive_entry *entry=NULL;
    if(!reader || archive_read_support_format_cab(reader)!=ARCHIVE_OK || archive_read_support_filter_none(reader)!=ARCHIVE_OK
            || archive_read_open_memory(reader,bytes,size)!=ARCHIVE_OK || archive_read_next_header(reader,&entry)!=ARCHIVE_OK) exit(2);
    if(read_data) {
        for(;;) {const void *data;size_t count;la_int64_t offset;int status=archive_read_data_block(reader,&data,&count,&offset);
            if(status==ARCHIVE_EOF)break;if(status!=ARCHIVE_OK)exit(3);}
    }
    if(archive_read_next_header(reader,&entry)!=ARCHIVE_OK)exit(4);
    long long result=(long long)archive_read_header_position(reader);archive_read_free(reader);return result;
}
int main(int argc,char **argv) {
    if(argc!=2)return 1;
    FILE *file=fopen(argv[1],"rb");if(!file)return 1;struct stat info;if(fstat(fileno(file),&info)||info.st_size<0||info.st_size>1024*1024)return 1;
    size_t size=(size_t)info.st_size;void *bytes=malloc(size);if(!bytes||fread(bytes,1,size,file)!=size)return 1;fclose(file);
    printf("{\"skips\":%lld,\"dataRead\":%lld}\n",position(bytes,size,0),position(bytes,size,1));free(bytes);return 0;
}
