// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Browse a ZIP-compatible input without expanding its directory tree on disk.
 * Selecting a member copies only that member to a private random filename.
 * Archive names are display data, never filesystem paths.
 */
public final class ArchiveExplorerActivity extends Activity {
 private static final int MAX_ENTRIES=20000;
 private static final long MAX_MEMBER=256L*1024*1024;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final List<Member> entries=new ArrayList<>();
 private File archive;
 private String prefix="";
 private ListView list;
 private TextView title,status;
 private EditText filter;
 private boolean busy;
 private static final class Member {
  final String name;final long size,compressed,crc;final int method;final boolean dir;
  Member(ZipEntry e){name=e.getName();size=e.getSize();compressed=e.getCompressedSize();crc=e.getCrc();method=e.getMethod();dir=e.isDirectory();}
 }
 private static final class Row {
  final String display,path;final Member member;
  Row(String d,String p,Member m){display=d;path=p;member=m;}
  public String toString(){return display;}
 }
 private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);
  String p=getIntent().getStringExtra("path");if(p==null){finish();return;}archive=new File(p);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  title=new TextView(this);title.setTextSize(18);root.addView(title);
  TextView help=new TextView(this);help.setText("ZIP central-directory metadata. Sizes and CRC are declarations until the selected member is decompressed. Entries are never installed or executed.");root.addView(help);
  root.addView(button("Parent directory",()->{if(busy)return;int slash=prefix.lastIndexOf('/',Math.max(0,prefix.length()-2));prefix=slash<0?"":prefix.substring(0,slash+1);render();}));
  filter=new EditText(this);filter.setSingleLine();filter.setHint("Filter this directory");root.addView(filter);
  status=new TextView(this);root.addView(status);list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  filter.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(android.text.Editable e){}});
  busy=true;status.setText("Reading central directory…");worker.execute(()->{
   List<Member> loaded=new ArrayList<>();String message;
   try(ZipFile zip=new ZipFile(archive)){Enumeration<? extends ZipEntry> es=zip.entries();while(es.hasMoreElements()&&loaded.size()<MAX_ENTRIES)loaded.add(new Member(es.nextElement()));message=loaded.size()+" entries"+(es.hasMoreElements()?" (inventory capped)":"");}
   catch(Exception e){message="Cannot parse ZIP directory: "+e.getMessage();}
   final String result=message;runOnUiThread(()->{if(isDestroyed())return;entries.addAll(loaded);busy=false;status.setText(result);render();});
  });
 }
 private void render(){
  if(list==null)return;title.setText("Archive /"+prefix);String q=filter.getText().toString().toLowerCase(Locale.ROOT);TreeMap<String,Row> dirs=new TreeMap<>();List<Row> files=new ArrayList<>();
  for(Member m:entries){
   if(!m.name.startsWith(prefix)||m.name.equals(prefix))continue;
   String rest=m.name.substring(prefix.length());int slash=rest.indexOf('/');
   if(slash>=0){String n=rest.substring(0,slash+1);if(!n.toLowerCase(Locale.ROOT).contains(q))continue;dirs.putIfAbsent(n,new Row("Directory: "+n,prefix+n,null));}
   else if(rest.toLowerCase(Locale.ROOT).contains(q)){String info=rest+"\n"+m.size+" bytes • compressed="+m.compressed+" • method="+m.method+" • CRC32="+Long.toHexString(m.crc);files.add(new Row(info,m.name,m));}
  }
  files.sort(Comparator.comparing(x->x.path));ArrayList<Row> rows=new ArrayList<>(dirs.values());rows.addAll(files);
  list.setAdapter(new ArrayAdapter<Row>(this,android.R.layout.simple_list_item_1,rows));list.setOnItemClickListener((p,v,i,id)->{if(busy)return;Row row=rows.get(i);if(row.member==null){prefix=row.path;filter.setText("");render();}else select(row.member);});
 }
 private void select(Member m){
  long same=entries.stream().filter(x->x.name.equals(m.name)).count();
  if(same!=1){Toast.makeText(this,"Ambiguous duplicate entry name; extraction disabled",Toast.LENGTH_LONG).show();return;}
  if(m.size<0||m.size>MAX_MEMBER){Toast.makeText(this,"Member exceeds 256 MiB or has unknown declared size",Toast.LENGTH_LONG).show();return;}
  busy=true;status.setText("Decompressing one selected member…");
  worker.execute(()->{
   File output=null;String error=null;
   try(ZipFile zip=new ZipFile(archive)){
    // Confirm uniqueness against the entire directory even if displayed inventory was capped.
    int found=0;ZipEntry selected=null;Enumeration<? extends ZipEntry> all=zip.entries();
    while(all.hasMoreElements()){ZipEntry e=all.nextElement();if(e.getName().equals(m.name)){selected=e;found++;if(found>1)break;}}
    if(found!=1||selected==null||selected.isDirectory())throw new IOException("Missing or duplicate archive member");
    String leaf=m.name.substring(m.name.lastIndexOf('/')+1);String suffix=".bin";int dot=leaf.lastIndexOf('.');if(dot>=0&&leaf.substring(dot).matches("\\.[A-Za-z0-9]{1,12}"))suffix=leaf.substring(dot);
    output=File.createTempFile("member-",suffix,getCacheDir());
    CRC32 crc=new CRC32();long written=0;byte[] buf=new byte[65536];
    try(InputStream in=zip.getInputStream(selected);FileOutputStream out=new FileOutputStream(output)){int n;while((n=in.read(buf))!=-1){written+=n;if(written>MAX_MEMBER||written>m.size)throw new IOException("Expanded member exceeds declared size or limit");out.write(buf,0,n);crc.update(buf,0,n);}}
    if(written!=m.size||crc.getValue()!=m.crc)throw new IOException("Member size or CRC mismatch");
   }catch(Exception e){error=e.getMessage();if(output!=null)output.delete();output=null;}
   final File result=output;final String failure=error;
   runOnUiThread(()->{if(isDestroyed()){if(result!=null)result.delete();return;}busy=false;if(result==null){status.setText("Extraction failed: "+failure);return;}setResult(RESULT_OK,new Intent().putExtra("path",result.getAbsolutePath()).putExtra("display",getIntent().getStringExtra("name")+"!/"+m.name));finish();});
  });
 }
 @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
