// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private LinearLayout root,pane,tabs;
 private TextView status;
 private Button open,export;
 private CheckBox deep,recursive,heuristic,aggressive,all;
 private EditText min;
 private Inspector.Result result;
 private String name="",report="",error="";
 private boolean busy;
 private Uri pending;
 private int page;
 private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
 private TextView txt(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xffe5eaf0);return t;}
 private TextView mono(String s){TextView t=txt(s,12);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);return t;}
 private Button btn(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 private CheckBox opt(LinearLayout l,String s,boolean b){CheckBox c=new CheckBox(this);c.setText(s);c.setChecked(b);l.addView(c);return c;}
 @Override public void onCreate(Bundle s){super.onCreate(s);ui();receive(getIntent());}
 @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);}
 private void ui(){
  root=vertical();root.setBackgroundColor(0xff15191e);root.setPadding(dp(12),dp(10),dp(12),dp(8));
  if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets a=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(a.left+dp(12),a.top+dp(10),a.right+dp(12),a.bottom+dp(8));return i;});}
  setContentView(root);TextView title=txt("DiE Android",26);title.setTypeface(null,Typeface.BOLD);root.addView(title);
  status=txt("Offline binary inspection • "+(Engine.AVAILABLE?"CDiE loaded":"Local inspector only"),13);root.addView(status);
  LinearLayout actions=new LinearLayout(this);
  open=btn("Open file",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,1);});
  export=btn("Export JSON",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"die-android-report.json");startActivityForResult(i,2);});
  export.setEnabled(false);actions.addView(open,new LinearLayout.LayoutParams(0,-2,1));actions.addView(export,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
  LinearLayout opts=vertical();opts.setVisibility(View.GONE);root.addView(btn("Scan options / information",()->opts.setVisibility(opts.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));
  deep=opt(opts,"Deep — additional signature checks",true);heuristic=opt(opts,"Heuristic — infer probable matches",true);recursive=opt(opts,"Recursive — inspect embedded content",false);aggressive=opt(opts,"Aggressive — slower, more speculative matches",false);all=opt(opts,"All types — parent format passes",false);
  min=new EditText(this);min.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);min.setText("4");opts.addView(txt("Minimum ASCII string length (3–64)",13));opts.addView(min);opts.addView(txt("Settings apply on next scan. Recursive and aggressive modes may consume significant resources.",12));root.addView(opts);
  tabs=new LinearLayout(this);HorizontalScrollView hs=new HorizontalScrollView(this);hs.addView(tabs);root.addView(hs);String[] labels={"Overview","DiE","Entropy","Strings","Hex"};for(int i=0;i<labels.length;i++){final int p=i;tabs.addView(btn(labels[i],()->{page=p;show();}));}
  ScrollView sc=new ScrollView(this);pane=vertical();sc.addView(pane);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));show();
 }
 private void receive(Intent i){if(i==null)return;Uri u=null;if(Intent.ACTION_VIEW.equals(i.getAction()))u=i.getData();else if(Intent.ACTION_SEND.equals(i.getAction()))u=i.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null&&"content".equals(u.getScheme()))scan(u);}
 @Override protected void onActivityResult(int req,int code,Intent data){super.onActivityResult(req,code,data);if(code==RESULT_OK&&data!=null&&data.getData()!=null){if(req==1)scan(data.getData());if(req==2)save(data.getData());}}
 private String fileName(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return "input.bin";}
 private void scan(Uri u){
  if(busy){pending=u;return;}busy=true;result=null;report="";error="";status.setText("Scanning locally…");open.setEnabled(false);export.setEnabled(false);show();
  int flags=(deep.isChecked()?1:0)|(heuristic.isChecked()?2:0)|(all.isChecked()?4:0)|(recursive.isChecked()?8:0)|(aggressive.isChecked()?32:0)|16;
  int m=4;try{m=Math.max(3,Math.min(64,Integer.parseInt(min.getText().toString())));}catch(Exception ignored){}final int minLen=m;
  worker.execute(()->{
   File f=null;Inspector.Result r=null;String n="",rep="",err="",fail="";
   try{
    n=fileName(u);String ext=".bin";int dot=n.lastIndexOf('.');if(dot>=0&&n.substring(dot).matches("\\.[a-zA-Z0-9]{1,12}"))ext=n.substring(dot);
    f=File.createTempFile("scan-",ext,getCacheDir());
    try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("No input data");byte[] b=new byte[65536];int count;long total=0;while((count=in.read(b))!=-1){total+=count;if(total>1024L*1024*1024)throw new IOException("Alpha limit: 1 GiB");out.write(b,0,count);}}
    r=Inspector.scan(f,minLen);
    if(Engine.AVAILABLE){try{File db=new File(getFilesDir(),"cdie-db-v1");File marker=new File(db,".ready");if(!marker.isFile()){copyAssets("db",db);if(!marker.createNewFile())throw new IOException("Cannot mark database ready");}rep=Engine.scan(f.getAbsolutePath(),db.getAbsolutePath(),flags);}catch(Exception|LinkageError e){err=e.toString();}}
    else err="CDiE not bundled; compiler/packer detection unavailable.";
   }catch(Exception e){fail=e.toString();}finally{if(f!=null)f.delete();}
   final Inspector.Result fr=r;final String fn=n,fp=rep,fe=err,ff=fail;
   runOnUiThread(()->{if(isDestroyed())return;result=fr;name=fn;report=fp;error=fe;busy=false;open.setEnabled(true);export.setEnabled(result!=null);status.setText(ff.isEmpty()?fn+" • "+(fe.isEmpty()?"scan complete":"local scan complete / engine unavailable"):ff);show();if(pending!=null){Uri next=pending;pending=null;scan(next);}});
  });
 }
 private void copyAssets(String path,File dest)throws IOException{String[] children=getAssets().list(path);if(children!=null&&children.length>0){if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create directory");for(String child:children)copyAssets(path+"/"+child,new File(dest,child));}else{try(InputStream in=getAssets().open(path);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}}
 private String pretty(String s){try{return new JSONObject(s).toString(2);}catch(JSONException e){try{return new JSONArray(s).toString(2);}catch(JSONException ignored){return s;}}}
 private void show(){
  pane.removeAllViews();for(int i=0;i<tabs.getChildCount();i++)tabs.getChildAt(i).setEnabled(i!=page);
  if(result==null){pane.addView(txt(busy?"Scan running on worker thread.":"Open or share a file to inspect it. No root or internet access required.",16));return;}
  switch(page){
   case 0:pane.addView(txt(name,20));pane.addView(txt(result.type,17));pane.addView(mono(String.format(Locale.ROOT,"Size: %,d bytes\nSHA-256:\n%s\nShannon entropy: %.4f bits/byte",result.size,result.sha256,result.entropy)));pane.addView(txt(error.isEmpty()?"CDiE report available in DiE tab.":error,14));break;
   case 1:pane.addView(txt("CDiE signature report (JSON)",17));pane.addView(mono(error.isEmpty()?pretty(report):error));break;
   case 2:pane.addView(txt(String.format(Locale.ROOT,"Whole-file entropy: %.3f / 8",result.entropy),17));TextView detail=txt("Touch graph to inspect a block.",14);pane.addView(new Graph(result,detail),new LinearLayout.LayoutParams(-1,dp(230)));pane.addView(detail);pane.addView(txt("High entropy can indicate compression, encryption or randomness; not proof of packing.",14));break;
   case 3:EditText q=new EditText(this);q.setSingleLine(true);q.setHint("Filter ASCII strings");pane.addView(q);pane.addView(txt("Preview limited to ~200k characters and 4096 characters per run.",12));TextView lines=mono(result.strings);pane.addView(lines);final String source=result.strings;q.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){StringBuilder o=new StringBuilder();String term=s.toString().toLowerCase(Locale.ROOT);for(String line:source.split("\n"))if(line.toLowerCase(Locale.ROOT).contains(term))o.append(line).append('\n');lines.setText(o.length()==0?"No matches":o.toString());}public void afterTextChanged(android.text.Editable e){}});break;
   case 4:pane.addView(txt("First 4096 bytes • hex / ASCII",14));HorizontalScrollView hs=new HorizontalScrollView(this);hs.addView(mono(result.hex));pane.addView(hs);break;
  }
 }
 private void save(Uri u){if(result==null)return;try{JSONObject o=new JSONObject();o.put("name",name);o.put("size",result.size);o.put("type",result.type);o.put("sha256",result.sha256);o.put("entropy",result.entropy);JSONArray a=new JSONArray();for(double v:result.blocks)a.put(v);o.put("blockEntropy",a);o.put("asciiStringsPreview",result.strings);o.put("hexPreview",result.hex);o.put("cdieRawReport",report);o.put("cdieError",error);try(OutputStream out=getContentResolver().openOutputStream(u,"wt")){if(out==null)throw new IOException("Cannot open destination");out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));}Toast.makeText(this,"Report saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
 private class Graph extends View{
  final Inspector.Result r;final TextView detail;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int selected=-1;
  Graph(Inspector.Result data,TextView t){super(MainActivity.this);r=data;detail=t;setContentDescription("Entropy graph, 0 to 8 bits per byte");}
  @Override protected void onDraw(Canvas c){float l=dp(30),right=getWidth()-dp(8),top=dp(12),bottom=getHeight()-dp(20);p.setTextSize(dp(12));for(int i=0;i<=8;i+=2){float y=bottom-(bottom-top)*i/8f;p.setColor(0xff58646f);c.drawLine(l,y,right,y,p);p.setColor(0xffd3dce5);c.drawText(""+i,dp(7),y,p);}if(r.blocks.length==0)return;float w=(right-l)/r.blocks.length;p.setColor(0xff80cbc4);for(int i=0;i<r.blocks.length;i++)c.drawRect(l+w*i,bottom-(bottom-top)*(float)r.blocks[i]/8,l+w*(i+1),bottom,p);if(selected>=0){p.setColor(0xffffce75);float x=l+w*(selected+.5f);c.drawLine(x,top,x,bottom,p);}}
  @Override public boolean onTouchEvent(MotionEvent e){if(r.blocks.length==0)return false;if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){selected=Math.max(0,Math.min(r.blocks.length-1,(int)((e.getX()-dp(30))*r.blocks.length/Math.max(1,getWidth()-dp(38)))));long block=Math.max(4096,(r.size+255)/256);detail.setText(String.format(Locale.ROOT,"Offset 0x%X • %.4f bits/byte",block*selected,r.blocks[selected]));invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
