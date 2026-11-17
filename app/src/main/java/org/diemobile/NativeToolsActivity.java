// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

public final class NativeToolsActivity extends Activity {
 private static final String[] TITLES={"Structures","Memory map","Imports","Exports","Symbols","Resources","Disassembly","Hashes","Unicode strings","Hex","Entropy regions"};
 private static final String[] HELP={
  "Native PE/ELF/Mach-O DataStructs, including field names and values. A parsed structure is evidence of file layout, not a claim about runtime behavior.",
  "Physical offsets refer to bytes in this file. Virtual addresses refer to the loader's address space before ASLR; virtual-only regions may have no bytes. Mode selects default, section or segment layout.",
  "Undefined or imported symbols identify referenced external interfaces. Dynamic resolution, statically linked code and obfuscation can conceal dependencies. A listed import is not a proven call.",
  "Exported symbols provide named or ordinal entry points for other modules. A forwarder redirects an import to another symbol. An export is not proof of execution.",
  "Symbols associate names with addresses or other values. Native kind, binding and storage fields describe the source table. Stripped files can legitimately have few symbols.",
  "Resource records describe embedded payload ranges, identifiers and language/code-page metadata. Payloads are not executed. Some formats have no supported resource table.",
  "Disassembly decodes bytes as instructions in the chosen architecture. Data can also decode as instructions, so linear decoding alone is not control-flow analysis. Auto starts at the engine-selected entry offset.",
  "CRC32 detects common transmission errors; MD5 and SHA-1 are legacy fingerprints with collision weaknesses; SHA-256 is a stronger content digest. None authenticates a publisher by itself.",
  "Printable sequences can reveal identifiers, paths and messages. ASCII/UTF-8/UTF-16 are encodings; coincidence and unused data are possible. The native extractor has explicit count and size limits.",
  "Hex displays exact bytes at physical offsets. It does not interpret them as instructions or text. Navigate the entire file in 4 KiB pages; this viewer never modifies the input.",
  "Region entropy calculates the byte histogram and Shannon entropy within mapped file structures. Samples show physical offset, size and entropy. High entropy alone does not prove encryption or packing."
 };
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private File file;private Spinner selector,mode;private EditText offset,minimum,filter,query;private CheckBox json,att;private TextView data,help,status;private Button run,next,prev,save,extract;private String exportSnapshot;private long exportStart,exportLength;
 private String full="";private long lastOffset=0,nextOffset=-1;private int generation;private boolean initialNavigation=true;
 private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
 private Button button(String t,Runnable r){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 private TextView text(String t,int size){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(0xffe5eaf0);return v;}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);String path=getIntent().getStringExtra("path");if(path==null){finish();return;}
  file=new File(path);if(!file.isFile()){finish();return;}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackgroundColor(0xff15191e);
  root.addView(text("Native analysis • "+getIntent().getStringExtra("name"),18));
  selector=new Spinner(this);selector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,TITLES));root.addView(selector);
  help=text("",13);root.addView(help);mode=new Spinner(this);root.addView(mode);
  LinearLayout fields=new LinearLayout(this);offset=new EditText(this);offset.setSingleLine();offset.setHint("Offset: auto or 0x…");fields.addView(offset,new LinearLayout.LayoutParams(0,-2,2));
  minimum=new EditText(this);minimum.setSingleLine();minimum.setInputType(2);minimum.setText("4");minimum.setHint("Min length");fields.addView(minimum,new LinearLayout.LayoutParams(0,-2,1));root.addView(fields);
  query=new EditText(this);query.setSingleLine();query.setHint("Structure or hash selector (optional)");root.addView(query);
  LinearLayout outputOptions=new LinearLayout(this);json=new CheckBox(this);json.setText("JSON");att=new CheckBox(this);att.setText("AT&T syntax");outputOptions.addView(json);outputOptions.addView(att);root.addView(outputOptions);
  LinearLayout actions=new LinearLayout(this);run=button("Analyze",()->analyze());prev=button("Previous",()->{offset.setText("0x"+Long.toHexString(Math.max(0,lastOffset-4096)));analyze();});next=button("Next",()->{if(nextOffset>=0&&nextOffset<file.length()){offset.setText("0x"+Long.toHexString(nextOffset));analyze();}});actions.addView(run);actions.addView(prev);actions.addView(next);root.addView(actions);
  LinearLayout more=new LinearLayout(this);save=button("Export report",()->exportReport());extract=button("Extract range",()->rangeDialog());more.addView(save);more.addView(extract);root.addView(more);
  filter=new EditText(this);filter.setSingleLine();filter.setHint("Filter result lines");root.addView(filter);status=text("Ready",12);root.addView(status);
  ScrollView scroll=new ScrollView(this);HorizontalScrollView horizontal=new HorizontalScrollView(this);data=text("",12);data.setTypeface(Typeface.MONOSPACE);data.setTextIsSelectable(true);horizontal.addView(data);scroll.addView(horizontal);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  filter.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(android.text.Editable e){}});
  selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int p,long id){generation++;help.setText(HELP[p]);full="";render();offset.setText("");nextOffset=-1;run.setEnabled(true);status.setText("Ready");String[] m=p==0?new String[]{"All structures","Structure inventory"}:p==1?new String[]{"Default","Sections","Segments"}:p==6?new String[]{"Auto","x86 16","x86 32","x86 64","ARM","Thumb","ARM64","ARM BE","Thumb BE","ARM64 BE"}:p==8?new String[]{"UTF-8 + UTF-16","ASCII","UTF-8","UTF-16LE","UTF-16BE"}:new String[]{"Default"};mode.setAdapter(new ArrayAdapter<>(NativeToolsActivity.this,android.R.layout.simple_spinner_dropdown_item,m));mode.setVisibility(p==0||p==1||p==6||p==8?View.VISIBLE:View.GONE);query.setText("");query.setVisibility(p==0||p==7?View.VISIBLE:View.GONE);query.setHint(p==0?"NAME[#FIELD] (blank = all)":"CRC32 / MD5 / SHA1 / SHA256");json.setVisibility(p==0||p==7||p==8||p==10?View.VISIBLE:View.GONE);att.setVisibility(p==6?View.VISIBLE:View.GONE);offset.setVisibility(p==6||p==9?View.VISIBLE:View.GONE);minimum.setVisibility(p==8?View.VISIBLE:View.GONE);prev.setVisibility(p==9?View.VISIBLE:View.GONE);next.setVisibility(p==6||p==9?View.VISIBLE:View.GONE);if(initialNavigation&&getIntent().hasExtra("tool")&&getIntent().getIntExtra("tool",0)==p){initialNavigation=false;offset.setText("0x"+Long.toHexString(getIntent().getLongExtra("offset",0)));data.post(NativeToolsActivity.this::analyze);}}});
  if(getIntent().hasExtra("tool"))selector.setSelection(Math.max(0,Math.min(TITLES.length-1,getIntent().getIntExtra("tool",0))));
 }
 private void render(){String q=filter.getText().toString().toLowerCase(Locale.ROOT);if(q.isEmpty()){data.setText(full);return;}StringBuilder b=new StringBuilder();for(String line:full.split("\n"))if(line.toLowerCase(Locale.ROOT).contains(q))b.append(line).append('\n');data.setText(b.toString());}
 private long parseOffset(String s){s=s.trim();if(s.isEmpty())return -1;long n=s.startsWith("0x")||s.startsWith("0X")?Long.parseLong(s.substring(2),16):Long.parseLong(s);if(n<0)throw new NumberFormatException("negative offset");return n;}
 private void analyze(){
  final int tool=selector.getSelectedItemPosition(), selected=mode.getSelectedItemPosition(), ticket=++generation;int m=4;long at;try{at=parseOffset(offset.getText().toString());m=Math.max(3,Math.min(64,Integer.parseInt(minimum.getText().toString())));}catch(Exception e){status.setText("Invalid offset or minimum length");return;}
  final long start=at;final int min=m;final String q=query.getText().toString().trim();final boolean j=json.isShown()&&json.isChecked(),syntax=att.isShown()&&att.isChecked();final int nativeMode=tool==8?new int[]{14,1,2,4,8}[Math.max(0,selected)]:selected;
  run.setEnabled(false);status.setText("Analyzing on worker…");worker.execute(()->{String r;try{r=tool==9?hex(file,Math.max(0,start)):Engine.tool(file.getAbsolutePath(),tool,start,nativeMode,min,q,j,syntax);}catch(Exception|LinkageError e){r="ERROR: "+e;}final String result=r;runOnUiThread(()->{if(isDestroyed()||ticket!=generation)return;full=result;lastOffset=Math.max(0,start);nextOffset=tool==9?Math.min(file.length(),lastOffset+4096):-1;for(String line:result.split("\n",5))if(line.startsWith("NEXT=0x"))try{nextOffset=Long.parseLong(line.substring(7).trim(),16);}catch(Exception ignored){}status.setText("Result • "+result.length()+" characters");run.setEnabled(true);render();});});
 }
 private void exportReport(){
  if(full.isEmpty()){Toast.makeText(this,"Analyze first",Toast.LENGTH_SHORT).show();return;}
  int tool=selector.getSelectedItemPosition();exportSnapshot=full;
  boolean j=json.isShown()&&json.isChecked()&&full.trim().startsWith("{");
  Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(j?"application/json":"text/plain");i.putExtra(Intent.EXTRA_TITLE,"die-"+TITLES[tool].toLowerCase(Locale.ROOT).replace(' ','-')+(j?".json":".txt"));startActivityForResult(i,1);
 }
 private void rangeDialog(){
  LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
  EditText start=new EditText(this);start.setSingleLine();start.setHint("Physical offset (decimal or 0x…)");start.setText("0x"+Long.toHexString(lastOffset));fields.addView(start);
  EditText length=new EditText(this);length.setSingleLine();length.setHint("Byte count (decimal or 0x…)");length.setText("4096");fields.addView(length);
  new android.app.AlertDialog.Builder(this).setTitle("Export a physical byte range").setMessage("Use resource or memory-map physical offsets, not virtual addresses. Up to 256 MiB, read-only.").setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Choose destination",(d,w)->{
   try{exportStart=parseOffset(start.getText().toString());exportLength=parseOffset(length.getText().toString());if(exportStart<0||exportLength<=0||exportLength>256L*1024*1024||exportStart>file.length()||exportLength>file.length()-exportStart)throw new IOException("Range outside file or limit");Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,"range-"+Long.toHexString(exportStart)+".bin");startActivityForResult(i,2);}catch(Exception ex){status.setText(ex.getMessage());}
  }).show();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();String report=exportSnapshot;long off=exportStart,len=exportLength;
  worker.execute(()->{String message;
   try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
    if(out==null)throw new IOException("Destination unavailable");
    if(request==1){if(report==null)throw new IOException("No report snapshot");out.write(report.getBytes(StandardCharsets.UTF_8));}
    else if(request==2)try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(off);byte[] buf=new byte[65536];long remaining=len;while(remaining>0){int n=in.read(buf,0,(int)Math.min(buf.length,remaining));if(n<0)throw new EOFException("Input ended early");out.write(buf,0,n);remaining-=n;}}
    message="Export saved";
   }catch(Exception ex){message="Export failed: "+ex.getMessage();}
   final String msg=message;runOnUiThread(()->{if(!isDestroyed())status.setText(msg);});
  });
 }
 static String hex(File file,long pos)throws IOException{
  try(RandomAccessFile in=new RandomAccessFile(file,"r")){if(pos<0||pos>in.length())throw new IOException("Offset outside input");in.seek(pos);StringBuilder b=new StringBuilder();byte[] row=new byte[16];for(int i=0;i<256;i++){int n=in.read(row);if(n<0)break;b.append(String.format(Locale.ROOT,"%016X  ",pos+i*16L));for(int j=0;j<16;j++)b.append(j<n?String.format(Locale.ROOT,"%02X ",row[j]&255):"   ");b.append(" |");for(int j=0;j<n;j++){int v=row[j]&255;b.append(v>=32&&v<127?(char)v:'.');}b.append("|\n");}return b.toString();}
 }
 @Override protected void onDestroy(){generation++;worker.shutdown();super.onDestroy();}
}
