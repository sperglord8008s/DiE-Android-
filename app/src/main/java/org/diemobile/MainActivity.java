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
 private TextView status,fileLabel;
 private Button open,rescan,export;
 private ProgressBar progress;
 private Uri selectedUri;
 private File retainedFile,ownedMember;
 private String selectedDisplayName;
 private Button nativeTools,archiveTools;
 private long elapsedMs;
 private boolean settingsDirty;
 private int scannedFlags,scannedMin;
 private LinearLayout optionPanel;
 private CheckBox deep,recursive,heuristic,aggressive,all;
 private EditText min;
 private Inspector.Result result;
 private String name="",report="",error="",guide="",apkReport="";
 private boolean busy;
 private Uri pending;
 private int page;
 private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
 private TextView txt(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xffe5eaf0);return t;}
 private TextView mono(String s){TextView t=txt(s,12);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);return t;}
 private Button btn(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setPadding(dp(8),0,dp(8),0);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 private CheckBox opt(LinearLayout l,String s,boolean b){CheckBox c=new CheckBox(this);c.setText(s);c.setTextSize(12);c.setChecked(b);l.addView(c,new LinearLayout.LayoutParams(0,dp(42),1));return c;}
 @Override public void onCreate(Bundle s){super.onCreate(s);ui();if(s!=null&&s.getParcelable("selected")!=null){selectedUri=s.getParcelable("selected");page=s.getInt("page",0);scan(selectedUri);}else receive(getIntent());}
 @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putParcelable("selected",selectedUri);state.putInt("page",page);}
 @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);}
 private void ui(){
  root=vertical();root.setBackgroundColor(0xff15191e);root.setPadding(dp(8),dp(6),dp(8),dp(4));
  if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets a=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(a.left+dp(8),a.top+dp(6),a.right+dp(8),a.bottom+dp(4));return i;});}
  setContentView(root);
  LinearLayout titlebar=new LinearLayout(this);titlebar.setGravity(Gravity.CENTER_VERTICAL);
  TextView title=txt("DiE Android",18);title.setTypeface(null,Typeface.BOLD);
  titlebar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  titlebar.addView(btn("Explore",()->startActivityForResult(new Intent(this,SystemExplorerActivity.class),4)));titlebar.addView(btn("Options",()->optionsDialog()));root.addView(titlebar);
  fileLabel=mono("No file selected");fileLabel.setTextSize(13);fileLabel.setPadding(dp(6),dp(8),dp(6),dp(8));fileLabel.setBackgroundColor(0xff222a31);fileLabel.setMaxLines(2);
  LinearLayout fileRow=new LinearLayout(this);fileRow.setGravity(Gravity.CENTER_VERTICAL);fileRow.addView(fileLabel,new LinearLayout.LayoutParams(0,-2,1));
  open=btn("…",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,1);});open.setContentDescription("Browse for a file");fileRow.addView(open);root.addView(fileRow);
  LinearLayout actions=new LinearLayout(this);rescan=btn("Scan / Rescan",()->{if(selectedUri!=null)scan(selectedUri);});rescan.setEnabled(false);
  export=btn("Save report",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"die-android-report.json");startActivityForResult(i,2);});
  export.setEnabled(false);actions.addView(rescan,new LinearLayout.LayoutParams(0,-2,1));actions.addView(export,new LinearLayout.LayoutParams(0,-2,1));nativeTools=btn("Tools",()->openNativeTools());nativeTools.setEnabled(false);actions.addView(nativeTools,new LinearLayout.LayoutParams(0,-2,1));archiveTools=btn("Archive",()->openArchive());archiveTools.setEnabled(false);actions.addView(archiveTools,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
  android.content.SharedPreferences prefs=getSharedPreferences("scan_options",MODE_PRIVATE);
  optionPanel=vertical();
  LinearLayout r1=new LinearLayout(this),r2=new LinearLayout(this);
  deep=opt(r1,"Deep",prefs.getBoolean("deep",true));heuristic=opt(r1,"Heuristic",prefs.getBoolean("heuristic",true));recursive=opt(r1,"Recursive",prefs.getBoolean("recursive",false));
  aggressive=opt(r2,"Aggressive",prefs.getBoolean("aggressive",false));all=opt(r2,"All types",prefs.getBoolean("all",false));
  optionPanel.addView(r1);optionPanel.addView(r2);root.addView(optionPanel);
  min=new EditText(this);min.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);min.setText(""+prefs.getInt("min",4));
  for(CheckBox c:new CheckBox[]{deep,heuristic,recursive,aggressive,all})c.setOnCheckedChangeListener((b,v)->settingsChanged());
  tabs=new LinearLayout(this);HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.addView(tabs);root.addView(hs);
  String[] labels={"Scan","File info","Reference","APK / SDKs","Entropy","Strings","Hex","Log / JSON"};for(int i=0;i<labels.length;i++){final int p=i;tabs.addView(btn(labels[i],()->{page=p;show();}));}
  View line=new View(this);line.setBackgroundColor(0xff44525e);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
  ScrollView sc=new ScrollView(this);pane=vertical();sc.addView(pane);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(0);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
  status=txt("Ready • "+(Engine.AVAILABLE?"CDiE loaded":"Local inspector only"),12);status.setPadding(dp(4),dp(4),dp(4),dp(2));root.addView(status);show();
 }
 private int currentFlags(){return (deep.isChecked()?1:0)|(heuristic.isChecked()?2:0)|(all.isChecked()?4:0)|(recursive.isChecked()?8:0)|(aggressive.isChecked()?32:0)|16;}
 private int minLength(){try{return Math.max(3,Math.min(64,Integer.parseInt(min.getText().toString())));}catch(Exception e){return 4;}}
 private void settingsChanged(){getSharedPreferences("scan_options",MODE_PRIVATE).edit().putBoolean("deep",deep.isChecked()).putBoolean("heuristic",heuristic.isChecked()).putBoolean("recursive",recursive.isChecked()).putBoolean("aggressive",aggressive.isChecked()).putBoolean("all",all.isChecked()).putInt("min",minLength()).apply();settingsDirty=currentFlags()!=scannedFlags||minLength()!=scannedMin;if(!busy&&selectedUri!=null&&settingsDirty){status.setText("Settings changed • press Scan / Rescan");}else if(!busy&&result!=null)finishedStatus();}
 private void optionsDialog(){LinearLayout form=vertical();form.setPadding(dp(18),dp(8),dp(18),dp(8));TextView desc=txt("Deep: additional signature rules.\nHeuristic: infer probable matches.\nRecursive: inspect embedded files.\nAggressive: additional speculative checks.\nAll types: also scan compatible parent formats.\n\nMinimum ASCII string length (3–64)",14);form.addView(desc);EditText entry=new EditText(this);entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);entry.setText(min.getText());form.addView(entry);form.addView(btn("Import Exodus database (JSON)",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,3);}));new AlertDialog.Builder(this).setTitle("Scan options").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{min.setText(entry.getText());min.setText(""+minLength());settingsChanged();}).show();}
 private void finishedStatus(){status.setText(String.format(Locale.ROOT,"%s • %d ms%s",error.isEmpty()?"Scan complete":"Local scan / engine error",elapsedMs,settingsDirty?" • settings changed":""));}
 private void receive(Intent i){if(i==null)return;Uri u=null;if(Intent.ACTION_VIEW.equals(i.getAction()))u=i.getData();else if(Intent.ACTION_SEND.equals(i.getAction()))u=i.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null&&"content".equals(u.getScheme()))scan(u);}
 @Override protected void onActivityResult(int req,int code,Intent data){super.onActivityResult(req,code,data);if((req==4||req==5)&&code==RESULT_OK&&data!=null){String p=data.getStringExtra("path");if(p!=null){if(req==5){if(ownedMember!=null)ownedMember.delete();ownedMember=new File(p);selectedDisplayName=data.getStringExtra("display");}scan(Uri.fromFile(new File(p)));}return;}if(code==RESULT_OK&&data!=null&&data.getData()!=null){if(req==1){try{getContentResolver().takePersistableUriPermission(data.getData(),data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}scan(data.getData());}if(req==2)save(data.getData());if(req==3)importExodus(data.getData());}}
 private void importExodus(Uri u){
  worker.execute(()->{String msg;File temp=new File(getFilesDir(),"exodus-trackers.tmp");try(InputStream in=getContentResolver().openInputStream(u)){
   if(in==null)throw new IOException("No input");byte[] data=KnowledgeReport.bytes(in,8*1024*1024);JSONObject obj=new JSONObject(new String(data,StandardCharsets.UTF_8));obj.getJSONObject("trackers");
   try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);}
   if(!temp.renameTo(new File(getFilesDir(),"exodus-trackers.json")))throw new IOException("Cannot store snapshot");
   msg="Exodus snapshot imported. Rescan to apply.";
  }catch(Exception e){temp.delete();msg="Import failed: "+e.getMessage();}final String text=msg;runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,text,Toast.LENGTH_LONG).show();});});
 }
 private String fileName(Uri u){if("file".equals(u.getScheme())){File f=new File(u.getPath());return ownedMember!=null&&f.equals(ownedMember)&&selectedDisplayName!=null?selectedDisplayName:f.getName();}try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return "input.bin";}
 private void scan(Uri u){
  if(busy){pending=u;return;}if(ownedMember!=null&&!("file".equals(u.getScheme())&&ownedMember.equals(new File(u.getPath())))){ownedMember.delete();ownedMember=null;selectedDisplayName=null;}if(retainedFile!=null){retainedFile.delete();retainedFile=null;}selectedUri=u;busy=true;result=null;report="";error="";guide="";apkReport="";status.setText("Scanning locally…");fileLabel.setText(u.getLastPathSegment()==null?"Selected file":u.getLastPathSegment());open.setEnabled(false);rescan.setEnabled(false);export.setEnabled(false);nativeTools.setEnabled(false);archiveTools.setEnabled(false);progress.setIndeterminate(true);show();
  int flags=currentFlags();final int minLen=minLength();final long started=SystemClock.elapsedRealtime();
  worker.execute(()->{
   File f=null;Inspector.Result r=null;String n="",rep="",err="",fail="",g="",a="";
   try{
    n=fileName(u);String ext=".bin";int dot=n.lastIndexOf('.');if(dot>=0&&n.substring(dot).matches("\\.[a-zA-Z0-9]{1,12}"))ext=n.substring(dot);
    f=File.createTempFile("scan-",ext,getCacheDir());
    try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("No input data");byte[] b=new byte[65536];int count;long total=0;while((count=in.read(b))!=-1){total+=count;if(total>1024L*1024*1024)throw new IOException("Alpha limit: 1 GiB");out.write(b,0,count);}}
    r=Inspector.scan(f,minLen);
    try{KnowledgeReport.Output k=KnowledgeReport.analyze(this,f,r,n);g=k.guide.toString();a=k.apk.toString();}catch(Exception e){g="Explanatory report unavailable: "+e.getMessage();}
    if(Engine.AVAILABLE){try{File db=new File(getFilesDir(),"cdie-db-v1");File marker=new File(db,".ready");if(!marker.isFile()){copyAssets("db",db);if(!marker.createNewFile())throw new IOException("Cannot mark database ready");}rep=Engine.scan(f.getAbsolutePath(),db.getAbsolutePath(),flags);}catch(Exception|LinkageError e){err=e.toString();}}
    else err="CDiE not bundled; compiler/packer detection unavailable.";
   }catch(Exception e){fail=e.toString();}finally{if(r==null&&f!=null){f.delete();f=null;}}
   final File inputCopy=f;final Inspector.Result fr=r;final String fn=n,fp=rep,fe=err,ff=fail,fg=g,fa=a;
   runOnUiThread(()->{if(isDestroyed()){if(inputCopy!=null)inputCopy.delete();return;}retainedFile=inputCopy;nativeTools.setEnabled(retainedFile!=null);archiveTools.setEnabled(retainedFile!=null&&fr!=null&&fr.type.contains("ZIP"));result=fr;name=fn;report=fp;error=fe;guide=fg;apkReport=fa;busy=false;elapsedMs=SystemClock.elapsedRealtime()-started;scannedFlags=flags;scannedMin=minLen;settingsDirty=currentFlags()!=flags||minLength()!=minLen;fileLabel.setText(fn.isEmpty()?"Selected file":fn);open.setEnabled(true);rescan.setEnabled(selectedUri!=null);export.setEnabled(result!=null);progress.setIndeterminate(false);progress.setProgress(result!=null?100:0);if(ff.isEmpty())finishedStatus();else status.setText(ff);show();if(pending!=null){Uri next=pending;pending=null;scan(next);}});
  });
 }
 private void openArchive(){if(retainedFile==null)return;Intent i=new Intent(this,ArchiveExplorerActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);startActivityForResult(i,5);}
 private void openNativeTools(){if(retainedFile==null)return;Intent i=new Intent(this,NativeToolsActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);startActivity(i);}
 private void copyAssets(String path,File dest)throws IOException{String[] children=getAssets().list(path);if(children!=null&&children.length>0){if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create directory");for(String child:children)copyAssets(path+"/"+child,new File(dest,child));}else{try(InputStream in=getAssets().open(path);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}}
 private String pretty(String s){try{return new JSONObject(s).toString(2);}catch(JSONException e){try{return new JSONArray(s).toString(2);}catch(JSONException ignored){return s;}}}
 private void show(){
  pane.removeAllViews();for(int i=0;i<tabs.getChildCount();i++){Button b=(Button)tabs.getChildAt(i);b.setEnabled(i!=page);b.setTextColor(i==page?0xff80cbc4:0xffe5eaf0);}
  if(result==null){pane.addView(txt(busy?"Scan running on worker thread.":"Open or share a file to inspect it. No root or internet access required.",16));return;}
  switch(page){
   case 0:field("Type",result.type,"Header-based format identification. A recognized magic value is a candidate, not validation of the complete file. Consult the signature groups below.");field("Size",String.format(Locale.ROOT,"%,d bytes (0x%X)",result.size,result.size),"Exact input length in decimal and hexadecimal. Offsets refer to byte positions within that length.");field("Entropy",String.format(Locale.ROOT,"%.4f / 8",result.entropy),"Shannon entropy of the observed byte histogram, from 0 to 8 bits per byte. Compression, encryption and ordinary media can all produce high entropy; this is not a packing or malware verdict.");heading("Detection results • CDiE");showDetections();heading("Content identity");field("SHA-256",result.sha256,"A 256-bit digest of the exact bytes analyzed. Compare against a trusted digest to identify matching content; it is not a publisher signature.");field("Flags",String.format(Locale.ROOT,"0x%02X",scannedFlags),"Actual engine flags captured when this scan began, not necessarily the settings now selected.");field("Time",elapsedMs+" ms","Elapsed wall-clock time including local copying, file inspection, knowledge lookup and signature scanning.");heading("Technical interpretation");pane.addView(btn("File structure and interpretation",()->{page=1;show();}));if(result.type.contains("ZIP")||result.type.contains("DEX"))pane.addView(btn("APK / DEX evidence and SDK details",()->{page=3;show();}));break;
   case 1:heading("File information");field("Name",name);field("Type",result.type);field("Size",String.format(Locale.ROOT,"%,d bytes (0x%X)",result.size,result.size));field("Entropy",String.format(Locale.ROOT,"%.4f bits/byte",result.entropy));heading("SHA-256");pane.addView(mono(result.sha256));heading("Structure and interpretation");readableReport(guide);break;
   case 2:heading("Technical reference");readableReport(guide);break;
   case 3:heading("APK / SDKs • static evidence");readableReport(apkReport);break;
   case 4:pane.addView(txt(String.format(Locale.ROOT,"Whole-file entropy: %.3f / 8",result.entropy),17));TextView detail=txt("Touch graph to inspect a block.",14);pane.addView(new Graph(result,detail),new LinearLayout.LayoutParams(-1,dp(230)));pane.addView(detail);pane.addView(txt("High entropy can indicate compression, encryption or randomness; not proof of packing.",14));break;
   case 5:EditText q=new EditText(this);q.setSingleLine(true);q.setHint("Filter ASCII strings");pane.addView(q);pane.addView(txt("Preview limited to ~200k characters and 4096 characters per run.",12));TextView lines=mono(result.strings);pane.addView(lines);final String source=result.strings;q.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){StringBuilder o=new StringBuilder();String term=s.toString().toLowerCase(Locale.ROOT);for(String line:source.split("\n"))if(line.toLowerCase(Locale.ROOT).contains(term))o.append(line).append('\n');lines.setText(o.length()==0?"No matches":o.toString());}public void afterTextChanged(android.text.Editable e){}});break;
   case 6:pane.addView(txt("First 4096 bytes • hex / ASCII",14));HorizontalScrollView hs=new HorizontalScrollView(this);hs.addView(mono(result.hex));pane.addView(hs);break;
   case 7:heading("Engine log / raw JSON");pane.addView(mono(error.isEmpty()?pretty(report):error+"\n"+pretty(report)));break;
  }
 }
 private void heading(String title){TextView h=txt(title,14);h.setTypeface(null,Typeface.BOLD);h.setTextColor(0xff80cbc4);h.setBackgroundColor(0xff26323a);h.setPadding(dp(8),dp(6),dp(8),dp(6));pane.addView(h,new LinearLayout.LayoutParams(-1,-2));}
 private void field(String key,String value){field(key,value,null);}
 private void field(String key,String value,String explanation){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.TOP);row.setPadding(dp(6),dp(5),dp(6),dp(5));TextView k=txt(key,13);k.setTextColor(0xffaab8c4);row.addView(k,new LinearLayout.LayoutParams(dp(78),-2));TextView v=txt(value,13);v.setTextIsSelectable(true);row.addView(v,new LinearLayout.LayoutParams(0,-2,1));if(explanation!=null){Button help=btn("?",()->explain(key,value,explanation));help.setContentDescription("Explain "+key);row.addView(help,new LinearLayout.LayoutParams(dp(40),dp(40)));}pane.addView(row);rule(pane);}
 private void explain(String title,String value,String explanation){LinearLayout body=vertical();body.setPadding(dp(18),dp(12),dp(18),dp(12));body.addView(mono(value));TextView e=txt(explanation,14);e.setTextIsSelectable(true);e.setPadding(0,dp(12),0,0);body.addView(e);ScrollView sc=new ScrollView(this);sc.addView(body);new AlertDialog.Builder(this).setTitle(title).setView(sc).setPositiveButton("Close",null).show();}
 private void readableReport(String content){
  // Preserve every line of the report; paragraphs use proportional text rather than one giant monospace block.
  String[] blocks=content.split("\\n\\s*\\n");for(String block:blocks){String trimmed=block.trim();if(trimmed.isEmpty())continue;TextView t=txt(trimmed,13);t.setTextIsSelectable(true);t.setPadding(dp(6),dp(5),dp(6),dp(5));pane.addView(t);}
 }
 private void rule(LinearLayout l){View rule=new View(this);rule.setBackgroundColor(0xff35414b);l.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));}
 private void showDetections(){
  if(!error.isEmpty()){pane.addView(txt(error,13));return;}
  try{
   JSONArray groups=new JSONObject(report).getJSONArray("detects");
   if(groups.length()==0){pane.addView(txt("No detection groups returned.",13));return;}
   int budget=500;
   for(int i=0;i<Math.min(groups.length(),100)&&budget>0;i++){
    JSONObject g=groups.optJSONObject(i);if(g==null)continue;
    JSONArray values=g.optJSONArray("values");
    String format=g.optString("filetype",g.optString("string","Unknown"));
    String member=g.optString("archive_member","");
    String part=g.optString("parentfilepart","");
    String title=format+(member.isEmpty()?"":" • "+member)+(part.isEmpty()||"Header".equals(part)?"":" ["+part+"]");
    String metadata=g.has("offset")?"Offset "+g.optString("offset")+" • "+g.optString("size","?")+" bytes":"";
    LinearLayout children=vertical();
    final boolean expanded=i==0||groups.length()<=3;
    children.setVisibility(expanded?View.VISIBLE:View.GONE);
    Button group=btn((expanded?"▾  ":"▸  ")+title,()->{});
    group.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);group.setTextSize(13);group.setTextColor(0xff80cbc4);
    group.setOnClickListener(v->{boolean show=children.getVisibility()!=View.VISIBLE;children.setVisibility(show?View.VISIBLE:View.GONE);group.setText((show?"▾  ":"▸  ")+title);});
    pane.addView(group,new LinearLayout.LayoutParams(-1,-2));pane.addView(children);
    if(!metadata.isEmpty()){TextView m=txt(metadata,11);m.setTextColor(0xff9baab5);m.setPadding(dp(18),0,dp(6),dp(4));children.addView(m);}
    if(values==null||values.length()==0){TextView no=txt("No signature records for this group.",12);no.setPadding(dp(20),dp(5),dp(4),dp(5));children.addView(no);continue;}
    for(int j=0;j<values.length()&&budget>0;j++,budget--){
     JSONObject d=values.optJSONObject(j);if(d==null)continue;
     String type=d.optString("type",""),n=d.optString("name",""),version=d.optString("version",""),info=d.optString("info","");
     String display=d.optString("string",n);
     LinearLayout row=vertical();row.setPadding(dp(18),dp(5),dp(6),dp(5));
     LinearLayout top=new LinearLayout(this);TextView cat=txt(type.isEmpty()?"Detection":type,12);cat.setTextColor(0xff9fc6dc);top.addView(cat,new LinearLayout.LayoutParams(dp(100),-2));
     TextView val=txt(n.isEmpty()?display:n+(version.isEmpty()?"":" ("+version+")"),13);top.addView(val,new LinearLayout.LayoutParams(0,-2,1));row.addView(top);
     if(!info.isEmpty()){TextView sub=txt(info,11);sub.setTextColor(0xffaab8c4);row.addView(sub);}
     Button help=btn("?",()->explain(type.isEmpty()?"Detection":type,display+"\n\n"+pretty(d.toString()),DetectionReference.describe(type)));help.setContentDescription("Explain "+type+" "+n);top.addView(help,new LinearLayout.LayoutParams(dp(40),dp(40)));row.setOnClickListener(v->help.performClick());children.addView(row);rule(children);
    }
   }
   if(budget==0||groups.length()>100)pane.addView(txt("Display capped at 100 groups / 500 records. Full report under Log / JSON.",12));
  }catch(JSONException e){pane.addView(txt("Unexpected engine report format. Inspect Log / JSON for the original result.",13));}
 }
 private void save(Uri u){if(result==null)return;try{JSONObject o=new JSONObject();o.put("name",name);o.put("size",result.size);o.put("type",result.type);o.put("sha256",result.sha256);o.put("entropy",result.entropy);o.put("scanFlags",scannedFlags);o.put("minAsciiStringLength",scannedMin);o.put("elapsedMs",elapsedMs);JSONArray a=new JSONArray();for(double v:result.blocks)a.put(v);o.put("blockEntropy",a);o.put("asciiStringsPreview",result.strings);o.put("hexPreview",result.hex);o.put("file101",guide);o.put("apkAnalysis",apkReport);o.put("cdieRawReport",report);o.put("cdieError",error);try(OutputStream out=getContentResolver().openOutputStream(u,"wt")){if(out==null)throw new IOException("Cannot open destination");out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));}Toast.makeText(this,"Report saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
 @Override protected void onDestroy(){worker.shutdown();if(retainedFile!=null)retainedFile.delete();if(ownedMember!=null)ownedMember.delete();super.onDestroy();}
 private class Graph extends View{
  final Inspector.Result r;final TextView detail;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int selected=-1;
  Graph(Inspector.Result data,TextView t){super(MainActivity.this);r=data;detail=t;setContentDescription("Entropy graph, 0 to 8 bits per byte");}
  @Override protected void onDraw(Canvas c){float l=dp(30),right=getWidth()-dp(8),top=dp(12),bottom=getHeight()-dp(20);p.setTextSize(dp(12));for(int i=0;i<=8;i+=2){float y=bottom-(bottom-top)*i/8f;p.setColor(0xff58646f);c.drawLine(l,y,right,y,p);p.setColor(0xffd3dce5);c.drawText(""+i,dp(7),y,p);}if(r.blocks.length==0)return;float w=(right-l)/r.blocks.length;p.setColor(0xff80cbc4);for(int i=0;i<r.blocks.length;i++)c.drawRect(l+w*i,bottom-(bottom-top)*(float)r.blocks[i]/8,l+w*(i+1),bottom,p);if(selected>=0){p.setColor(0xffffce75);float x=l+w*(selected+.5f);c.drawLine(x,top,x,bottom,p);}}
  @Override public boolean onTouchEvent(MotionEvent e){if(r.blocks.length==0)return false;if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){selected=Math.max(0,Math.min(r.blocks.length-1,(int)((e.getX()-dp(30))*r.blocks.length/Math.max(1,getWidth()-dp(38)))));long block=Math.max(4096,(r.size+255)/256);detail.setText(String.format(Locale.ROOT,"Offset 0x%X • %.4f bits/byte",block*selected,r.blocks[selected]));invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
