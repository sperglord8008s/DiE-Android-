// SPDX-License-Identifier: GPL-3.0-only
/* Thin, bounded Android adapter for pinned CDiE's independent analysis modules. */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <limits.h>
#include <xxfclib/buf/xx_buf.h>
#include <xxfclib/data/xx_pd.h>
#include "app/cdie_file_info.h"
#include "app/cdie_memory_map.h"
#include "app/cdie_imports.h"
#include "app/cdie_exports.h"
#include "app/cdie_symbols.h"
#include "app/cdie_resources.h"
#include "app/cdie_disasm.h"
#include "app/cdie_hash.h"
#include "app/cdie_entropy.h"
#include "app/cdie_strings.h"
#define LIMIT (4U*1024U*1024U)
#define U(x) ((unsigned long long)(x))
#define I(x) ((long long)(x))
#define S(x) ((x)?(x):"")
static jbyteArray bytes(JNIEnv *env,const char *s,size_t n){
 if(n>INT_MAX)return NULL;
 jbyteArray a=(*env)->NewByteArray(env,(jsize)n);
 if(a&&n)(*env)->SetByteArrayRegion(env,a,0,(jsize)n,(const jbyte*)s);
 return a;
}
static int room(xx_buf_t *b){return xx_buf_ok(b)&&b->size<LIMIT;}
static void offset(xx_buf_t *b,int64_t off,uint64_t addr){
 if(off<0)xx_buf_append_str(b,"off=unmapped ");else xx_buf_appendf(b,"off=0x%llX ",U(off));
 if(addr==UINT64_MAX)xx_buf_append_str(b,"va=unknown ");else xx_buf_appendf(b,"va=0x%llX ",U(addr));
}
JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_toolNative(JNIEnv *env,jclass cls,jstring path,jint tool,jlong at,jint mode,jint min,jstring filter,jint json,jint syntax){
 (void)cls;
 if(!path)return NULL;
 const char *p=(*env)->GetStringUTFChars(env,path,NULL);if(!p)return NULL;
 const char *query=filter?(*env)->GetStringUTFChars(env,filter,NULL):NULL;
 if(filter&&!query){(*env)->ReleaseStringUTFChars(env,path,p);return NULL;}
 cdie_output_format format=json?CDIE_OUTPUT_JSON:CDIE_OUTPUT_TEXT;
 xx_pd_struct pd=xx_pd_init();
 xx_buf_t b;xx_buf_init(&b);
 char *err=NULL,*text=NULL;int ok=0;size_t i=0,count=0;
 switch(tool){
 case 0:
  ok=cdie_file_info(p,query&&*query?query:"Info",mode==1,format,&text,&err);break;
 case 1:{
  cdie_memory_map_data *d=NULL;
  ok=cdie_memory_map_read_mode(p,NULL,mode==1?"Sections":mode==2?"Segments":NULL,&pd,&d,&err);
  if(ok&&d){xx_buf_appendf(&b,"%s | %s | %s | %s | mode=%s\n",d->file_type,d->architecture,d->bitness,d->endianness,d->mode);
   xx_buf_appendf(&b,"Module VA=0x%llX | Entry VA=0x%llX | available modes mask=0x%X\n",U(d->module_address),U(d->entry_point_address),d->available_modes);
   count=d->count;for(i=0;i<count&&room(&b);i++){xx_memory_record *r=&d->records[i];offset(&b,r->offset,r->address);xx_buf_appendf(&b,"size=0x%llX part=0x%X part-number=%d index=%d virtual=%d invisible=%d %s\n",U(r->size),r->file_part,r->file_part_number,r->index,r->is_virtual,r->is_invisible,r->name);}
  }xx_rt_free(d);break;}
 case 2:{
  cdie_imports_data *d=NULL;ok=cdie_imports_read(p,&pd,&d,&err);
  if(ok&&d){count=d->count;xx_buf_appendf(&b,"%s | %llu records\n",d->file_type,U(count));for(i=0;i<count&&room(&b);i++){xx_import_record *r=&d->records[i];offset(&b,r->offset,r->address);xx_buf_appendf(&b,"%s!%s ordinal=%llu hint=%u by-ordinal=%d delay=%d\n",S(r->library_name),S(r->name),U(r->ordinal),r->hint,r->by_ordinal,r->is_delay);}}
  cdie_imports_free(d);break;}
 case 3:{
  cdie_exports_data *d=NULL;ok=cdie_exports_read(p,&pd,&d,&err);
  if(ok&&d){count=d->count;xx_buf_appendf(&b,"%s | %llu records\n",d->file_type,U(count));for(i=0;i<count&&room(&b);i++){xx_export_record *r=&d->records[i];offset(&b,r->offset,r->address);xx_buf_appendf(&b,"%s ordinal=",S(r->name));if(r->ordinal==UINT64_MAX)xx_buf_append_str(&b,"unknown");else xx_buf_appendf(&b,"%llu",U(r->ordinal));xx_buf_appendf(&b," forwarder=%s\n",S(r->forwarder));}}
  cdie_exports_free(d);break;}
 case 4:{
  cdie_symbols_data *d=NULL;ok=cdie_symbols_read(p,&pd,&d,&err);
  if(ok&&d){count=d->count;xx_buf_appendf(&b,"%s | %llu records\n",d->file_type,U(count));for(i=0;i<count&&room(&b);i++){xx_symbol_record *r=&d->records[i];offset(&b,r->offset,r->address);xx_buf_appendf(&b,"%s kind=%d binding=%d value=0x%llX size=%llu section=%d table-index=%llu record-off=%lld native-type=0x%X storage=%u aux=%u undefined=%d absolute=%d debug=%d file=%s\n",S(r->name),r->kind,r->binding,U(r->value),U(r->size),r->section_number,U(r->table_index),I(r->record_offset),r->native_type,r->native_storage_class,r->auxiliary_count,r->is_undefined,r->is_absolute,r->is_debug,S(r->file_name));}}
  cdie_symbols_free(d);break;}
 case 5:{
  cdie_resources_data *d=NULL;ok=cdie_resources_read(p,&pd,&d,&err);
  if(ok&&d){count=d->count;xx_buf_appendf(&b,"%s | %llu records\n",d->file_type,U(count));for(i=0;i<count&&room(&b);i++){xx_resource_record *r=&d->records[i];xx_buf_appendf(&b,"off=%lld size=%lld type=%u (%s) name=%u (%s) language=%u (%s) code-page=%u\n",I(r->offset),I(r->size),r->type_id,S(r->type_name),r->name_id,S(r->name),r->language_id,S(r->language_name),r->code_page);}}
  cdie_resources_free(d);break;}
 case 6:{
  cdie_disasm_context *d=NULL;ok=cdie_disasm_open(p,&pd,&d,&err);
  if(ok&&d){cdie_disasm_set_syntax(d,syntax==1?1:0);const cdie_disasm_info *info=cdie_disasm_get_info(d);const cdie_disasm_page *page=NULL;uint64_t pos=at<0?info->initial_offset:(uint64_t)at;
   size_t m=mode<0?0:(size_t)mode;cdie_disasm_status st=cdie_disasm_load(d,pos,m,&page);
   xx_buf_appendf(&b,"%s | file-size=%llu | mode=%s\n",info->description,U(info->file_size),cdie_disasm_mode_name(m));
   if(st==CDIE_DISASM_OK&&page){xx_buf_appendf(&b,"NEXT=0x%llX\n",U(page->next_offset));count=page->count;for(i=0;i<count&&room(&b);i++){const cdie_disasm_row *r=&page->rows[i];offset(&b,(int64_t)r->offset,r->address);xx_buf_appendf(&b,"%s  %s  %s\n",r->bytes,r->instruction,r->comment);}}
   else xx_buf_appendf(&b,"Disassembly status %d. Select an architecture or a valid file offset.\n",st);
  }cdie_disasm_close(d);break;}
 case 7:ok=cdie_hash_report(p,query&&*query?query:NULL,format,&text,&err);break;
 case 8:{cdie_strings_options opts=cdie_strings_defaults();opts.minimum_length=(unsigned)(min>=3&&min<=64?min:4);opts.encodings=(unsigned)(mode==1||mode==2||mode==4||mode==8||mode==14?mode:14);ok=cdie_strings_report(p,&opts,format,&text,&err);break;}
 case 10:ok=cdie_entropy_report(p,format,&text,&err);break;
 default:xx_buf_append_str(&b,"Unknown analysis tool.\n");ok=1;break;
 }
 if(query)(*env)->ReleaseStringUTFChars(env,filter,query);
 (*env)->ReleaseStringUTFChars(env,path,p);
 jbyteArray out=NULL;
 if(!ok){const char *e=err?err:"Native analysis unavailable for this input";xx_buf_clear(&b);xx_buf_appendf(&b,"ERROR: %s\n",e);}
 else if(text){size_t n=strlen(text);if(n<=LIMIT)out=bytes(env,text,n);else xx_buf_append_str(&b,"ERROR: native report exceeds 4 MiB display limit; no partial report returned.\n");}
 else if(i<count)xx_buf_appendf(&b,"\nPARTIAL: %llu of %llu records displayed (output cap).\n",U(i),U(count));
 if(!out&&xx_buf_ok(&b))out=bytes(env,b.data?b.data:"",b.size);
 xx_buf_free(&b);xx_rt_free(text);xx_rt_free(err);return out;
}
