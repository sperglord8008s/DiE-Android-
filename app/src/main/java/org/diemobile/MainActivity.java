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
 private boolean settingsDirty,loadingSettings;
 private ScanOptions scannedOptions;
 private ScanEvidence evidence;
 private JSONObject nativeFacts;
 private EngineDatabase database;
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
 private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
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
  ScanOptions prefs=ScanPreferences.load(this);
  optionPanel=vertical();
  LinearLayout r1=new LinearLayout(this),r2=new LinearLayout(this);
  deep=opt(r1,"Deep",(prefs.flags&1)!=0);heuristic=opt(r1,"Heuristic",(prefs.flags&2)!=0);recursive=opt(r1,"Recursive",(prefs.flags&8)!=0);
  aggressive=opt(r2,"Aggressive",(prefs.flags&32)!=0);all=opt(r2,"All types",(prefs.flags&4)!=0);
  optionPanel.addView(r1);optionPanel.addView(r2);root.addView(optionPanel);
  min=new EditText(this);min.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);min.setText(""+prefs.minimumStringLength);
  for(CheckBox c:new CheckBox[]{deep,heuristic,recursive,aggressive,all})c.setOnCheckedChangeListener((b,v)->settingsChanged());
  tabs=new LinearLayout(this);HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.addView(tabs);root.addView(hs);
  String[] labels={"Scan","File info","Reference","APK / SDKs","Entropy","Strings","Hex","Log / JSON"};for(int i=0;i<labels.length;i++){final int p=i;tabs.addView(btn(labels[i],()->{page=p;show();}));}
  View line=new View(this);line.setBackgroundColor(0xff44525e);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
  ScrollView sc=new ScrollView(this);pane=vertical();sc.addView(pane);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(0);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
  status=txt("Ready • "+(Engine.AVAILABLE?"CDiE loaded":"Local inspector only"),12);status.setPadding(dp(4),dp(4),dp(4),dp(2));root.addView(status);show();
 }
 private ScanOptions currentOptions(){return ScanPreferences.load(this).withBasicFlags((deep.isChecked()?1:0)|(heuristic.isChecked()?2:0)|(all.isChecked()?4:0)|(recursive.isChecked()?8:0)|(aggressive.isChecked()?32:0),minLength());}
 private int currentFlags(){return currentOptions().flags;}
 @Override protected void onResume(){super.onResume();if(deep==null)return;loadingSettings=true;ScanOptions o=ScanPreferences.load(this);deep.setChecked((o.flags&1)!=0);heuristic.setChecked((o.flags&2)!=0);all.setChecked((o.flags&4)!=0);recursive.setChecked((o.flags&8)!=0);aggressive.setChecked((o.flags&32)!=0);min.setText(""+o.minimumStringLength);loadingSettings=false;settingsChanged();}
 private int minLength(){try{return Math.max(3,Math.min(64,Integer.parseInt(min.getText().toString())));}catch(Exception e){return 4;}}
 private void settingsChanged(){if(loadingSettings)return;ScanPreferences.save(this,currentOptions());settingsDirty=scannedOptions!=null&&!currentOptions().sameAs(scannedOptions);if(!busy&&selectedUri!=null&&settingsDirty){status.setText("Settings changed • press Scan / Rescan");}else if(!busy&&result!=null)finishedStatus();}
 private void optionsDialog(){LinearLayout form=vertical();form.setPadding(dp(18),dp(8),dp(18),dp(8));TextView desc=txt("Deep: additional signature rules.\nHeuristic: infer probable matches.\nRecursive: inspect embedded files.\nAggressive: additional speculative checks.\nAll types: also scan compatible parent formats.\n\nMinimum ASCII string length (3–64)",14);form.addView(desc);EditText entry=new EditText(this);entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);entry.setText(min.getText());form.addView(entry);form.addView(btn("Import Exodus database (JSON)",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,3);}));new AlertDialog.Builder(this).setTitle("Scan options").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{min.setText(entry.getText());min.setText(""+minLength());settingsChanged();}).show();}
 private void finishedStatus(){status.setText(String.format(Locale.ROOT,"%s • %d ms%s",error.isEmpty()?"Scan complete":"Local scan / engine error",elapsedMs,settingsDirty?" • settings changed":""));}
 private void receive(Intent i){if(i==null)return;Uri u=null;if(Intent.ACTION_VIEW.equals(i.getAction()))u=i.getData();else if(Intent.ACTION_SEND.equals(i.getAction()))u=i.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null&&"content".equals(u.getScheme()))scan(u);}
 @Override protected void onActivityResult(int req,int code,Intent data){super.onActivityResult(req,code,data);if((req==4||req==5)&&code==RESULT_OK&&data!=null){String p=data.getStringExtra("path");if(p!=null){if(req==5){if(ownedMember!=null)ownedMember.delete();ownedMember=new File(p);selectedDisplayName=data.getStringExtra("display");}scan(Uri.fromFile(new File(p)));}return;}if(code==RESULT_OK&&data!=null&&data.getData()!=null){if(req==1){try{if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}scan(data.getData());}if(req==2)save(data.getData());if(req==3)importExodus(data.getData());}}
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
  if(busy){pending=u;return;}if(ownedMember!=null&&!("file".equals(u.getScheme())&&ownedMember.equals(new File(u.getPath())))){ownedMember.delete();ownedMember=null;selectedDisplayName=null;}if(retainedFile!=null){retainedFile.delete();retainedFile=null;}selectedUri=u;busy=true;result=null;evidence=null;nativeFacts=null;database=null;report="";error="";guide="";apkReport="";status.setText("Scanning locally…");fileLabel.setText(u.getLastPathSegment()==null?"Selected file":u.getLastPathSegment());open.setEnabled(false);rescan.setEnabled(false);export.setEnabled(false);nativeTools.setEnabled(false);archiveTools.setEnabled(false);progress.setIndeterminate(true);show();
  final ScanOptions options=currentOptions();int flags=options.flags;final int minLen=options.minimumStringLength;final long started=SystemClock.elapsedRealtime();
  worker.execute(()->{
   File f=null;Inspector.Result r=null;String n="",rep="",err="",fail="",g="",a="";ScanEvidence ev=null;JSONObject facts=null;EngineDatabase databases=null;
   try{
    n=fileName(u);String ext=".bin";int dot=n.lastIndexOf('.');if(dot>=0&&n.substring(dot).matches("\\.[a-zA-Z0-9]{1,12}"))ext=n.substring(dot);
    f=File.createTempFile("scan-",ext,getCacheDir());
    try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("No input data");byte[] b=new byte[65536];int count;long total=0;while((count=in.read(b))!=-1){total+=count;if(total>1024L*1024*1024)throw new IOException("Alpha limit: 1 GiB");out.write(b,0,count);}}
    r=Inspector.scan(f,minLen);
    try{KnowledgeReport.Output k=KnowledgeReport.analyze(this,f,r,n);g=k.guide.toString();a=k.apk.toString();}catch(Exception e){g="Explanatory report unavailable: "+e.getMessage();}
    if(Engine.AVAILABLE){try{
     databases=EngineDatabase.prepare(this);
     rep=Engine.desktop(f.getAbsolutePath(),databases.main.getAbsolutePath(),databases.custom.getAbsolutePath(),1,flags,0,options.includeTags,options.excludeTags,"","");
     ev=ScanEvidence.parse(rep);if(!ev.ok)err=ev.error;
     facts=new JSONObject(Engine.desktop(f.getAbsolutePath(),databases.main.getAbsolutePath(),databases.custom.getAbsolutePath(),5,flags,0,options.includeTags,options.excludeTags,"",""));
    }catch(Exception|LinkageError e){err=e.toString();}}
    else err="CDiE not bundled; compiler/packer detection unavailable.";
   }catch(Exception e){fail=e.toString();}finally{if(r==null&&f!=null){f.delete();f=null;}}
   final ScanEvidence finalEvidence=ev;final JSONObject finalFacts=facts;final EngineDatabase finalDatabases=databases;
   final File inputCopy=f;final Inspector.Result fr=r;final String fn=n,fp=rep,fe=err,ff=fail,fg=g,fa=a;
   runOnUiThread(()->{if(isDestroyed()){if(inputCopy!=null)inputCopy.delete();return;}retainedFile=inputCopy;nativeTools.setEnabled(retainedFile!=null);archiveTools.setEnabled(retainedFile!=null&&fr!=null&&fr.type.contains("ZIP"));result=fr;evidence=finalEvidence;nativeFacts=finalFacts;database=finalDatabases;scannedOptions=options;name=fn;report=fp;error=fe;guide=fg;apkReport=fa;busy=false;elapsedMs=SystemClock.elapsedRealtime()-started;scannedFlags=flags;scannedMin=minLen;settingsDirty=!currentOptions().sameAs(options);fileLabel.setText(fn.isEmpty()?"Selected file":fn);open.setEnabled(true);rescan.setEnabled(selectedUri!=null);export.setEnabled(result!=null);progress.setIndeterminate(false);progress.setProgress(result!=null?100:0);if(ff.isEmpty())finishedStatus();else status.setText(ff);show();if(pending!=null){Uri next=pending;pending=null;scan(next);}});
  });
 }
 private void openDesktopScan(){if(retainedFile==null||!Engine.AVAILABLE)return;Intent i=new Intent(this,DesktopScanActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);startActivity(i);}
 private void openArchive(){if(retainedFile==null)return;Intent i=new Intent(this,ArchiveExplorerActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);startActivityForResult(i,5);}
 private void openNativeTools(){if(retainedFile==null)return;Intent i=new Intent(this,NativeToolsActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);startActivity(i);}
 private void openNativeToolAt(int tool,long offset){if(retainedFile==null)return;Intent i=new Intent(this,NativeToolsActivity.class);i.putExtra("path",retainedFile.getAbsolutePath());i.putExtra("name",name);i.putExtra("tool",tool);i.putExtra("offset",offset);startActivity(i);}
 private void showNativeFacts(){
  if(evidence==null||!evidence.ok)return;
  field("Architecture",evidence.architecture+" / "+evidence.mode+" / "+evidence.endianness,"Parser-reported architecture, execution mode and byte order. These describe the file, not this Android device or a runtime process.");
  if(nativeFacts!=null&&nativeFacts.optBoolean("ok")){
   field("Image base",nativeFacts.optString("base"),"Preferred loader address before relocation or ASLR. A virtual address is not a physical file offset.");
   field("Entry VA",nativeFacts.optString("entry"),"Format-declared entry point in the virtual address domain. Loader initialization and other entry paths may run first.");
   long entry=nativeFacts.optLong("entryOffset",-1);
   if(entry>=0){field("Entry offset",String.format(Locale.ROOT,"0x%X",entry),"Physical byte offset mapped by the native parser. Inspect bytes and instruction decoding to corroborate the entry point.");pane.addView(btn("Hex at entry point",()->openNativeToolAt(9,entry)));pane.addView(btn("Disassemble entry point",()->openNativeToolAt(6,entry)));}
  }
  if(database!=null)field("Database",database.signatureRevision,"Exact bundled Detect-It-Easy signature database Git revision. The engine revision and options are preserved in report exports.");
 }
 private String pretty(String s){try{return new JSONObject(s).toString(2);}catch(JSONException e){try{return new JSONArray(s).toString(2);}catch(JSONException ignored){return s;}}}
 private void show(){
  pane.removeAllViews();for(int i=0;i<tabs.getChildCount();i++){Button b=(Button)tabs.getChildAt(i);b.setEnabled(i!=page);b.setTextColor(i==page?0xff80cbc4:0xffe5eaf0);}
  if(result==null){pane.addView(txt(busy?"Scan running on worker thread.":"Open or share a file to inspect it. No root or internet access required.",16));return;}
  switch(page){
   case 0:field("Type",evidence!=null&&evidence.ok?evidence.type:result.type,"Desktop parser interpretation when available; the local header identification is retained in File info and exported reports. Recognition does not validate every structure.");showNativeFacts();field("Size",String.format(Locale.ROOT,"%,d bytes (0x%X)",result.size,result.size),"Exact input length in decimal and hexadecimal. Offsets refer to byte positions within that length.");field("Entropy",String.format(Locale.ROOT,"%.4f / 8",result.entropy),"Shannon entropy of the observed byte histogram, from 0 to 8 bits per byte. Compression, encryption and ordinary media can all produce high entropy; this is not a packing or malware verdict.");heading("Detection results • desktop CDiE");pane.addView(btn("Desktop scan laboratory / signature editor",()->openDesktopScan()));showDetections();heading("Content identity");field("SHA-256",result.sha256,"A 256-bit digest of the exact bytes analyzed. Compare against a trusted digest to identify matching content; it is not a publisher signature.");field("Flags",String.format(Locale.ROOT,"0x%04X",scannedFlags),"Actual desktop-backend flags captured when the scan began. Laboratory options and tag filters apply here too. The export preserves their names and values.");field("Time",elapsedMs+" ms","Elapsed wall-clock time including local copying, file inspection, knowledge lookup and signature scanning.");heading("Technical interpretation");pane.addView(btn("File structure and interpretation",()->{page=1;show();}));if(result.type.contains("ZIP")||result.type.contains("DEX"))pane.addView(btn("APK / DEX evidence and SDK details",()->{page=3;show();}));break;
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
  if(evidence==null){pane.addView(txt("No desktop scan evidence available.",13));return;}
  if(!evidence.log.isEmpty()){heading("Engine diagnostics");pane.addView(mono(evidence.log));}
  pane.addView(txt(evidence.rows.size()+" tree rows • "+evidence.signatureCount+" loaded rules. Tap a record for evidence and interpretation.",12));
  EditText search=new EditText(this);search.setSingleLine();search.setHint("Search detections, categories or rule paths");pane.addView(search);
  LinearLayout rows=vertical();pane.addView(rows);renderEvidence(rows,"",0);
  search.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence s,int a,int c,int d){}
   public void onTextChanged(CharSequence s,int a,int b,int c){renderEvidence(rows,s.toString(),0);}
   public void afterTextChanged(android.text.Editable e){}
  });
 }
 private void renderEvidence(LinearLayout target,String query,int page){
  target.removeAllViews();int total=0;
  String q=query.toLowerCase(Locale.ROOT);
  for(ScanEvidence.Row row:evidence.rows)if((row.text+"\n"+row.category+"\n"+row.signature).toLowerCase(Locale.ROOT).contains(q))total++;
  int pages=Math.max(1,(total+49)/50);target.addView(txt(total+" matching rows • page "+(page+1)+" / "+pages,12));
  for(ScanEvidence.Row row:evidence.page(query,page,50)){
   String indent="";for(int d=0;d<Math.min(row.depth,4);d++)indent+="  ";
   String label=indent+row.text+(row.signature.isEmpty()?"":"\n"+row.signature);
   Button record=btn(label,()->explain(row.category.isEmpty()?"Detection evidence":row.category,
    row.text+"\nCategory: "+row.category+"\nName: "+row.name+"\nRule source: "+(row.signature.isEmpty()?"No script source attached to this group":row.signature)+"\nTree row: "+row.index+"; parent: "+row.parent,
    DetectionReference.describe(row.category)+"\n\nThe path is the exact upstream rule provenance, not a byte-match location. Use the laboratory to read or test the source. Group offsets may describe decompressed archive members rather than bytes in this input; do not treat them as physical offsets without checking their address basis."));
   record.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);record.setPadding(dp(8),dp(8),dp(8),dp(8));target.addView(record,new LinearLayout.LayoutParams(-1,-2));
  }
  LinearLayout navigation=new LinearLayout(this);
  Button previous=btn("Previous 50",()->renderEvidence(target,query,page-1));previous.setEnabled(page>0);navigation.addView(previous);
  Button next=btn("Next 50",()->renderEvidence(target,query,page+1));next.setEnabled(page+1<pages);navigation.addView(next);target.addView(navigation);
 }
 private void save(Uri uri){
  if(result==null||scannedOptions==null)return;
  final Inspector.Result source=result;final ScanOptions options=scannedOptions;final ScanEvidence scan=evidence;
  final JSONObject facts=nativeFacts;final EngineDatabase db=database;final String inputName=name,explanation=guide,apk=apkReport,engineError=error;
  final long duration=elapsedMs;
  worker.execute(()->{
   String message;
   try{
    JSONObject output=ScanReport.create(inputName,source,options,scan,facts,db==null?"":db.engineRevision,db==null?"":db.signatureRevision,duration,explanation,apk,engineError);
    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
     if(out==null)throw new IOException("Cannot open destination");out.write(output.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    message="Report saved";
   }catch(Exception e){message="Export failed: "+e;}
   final String text=message;runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,text,Toast.LENGTH_LONG).show();});
  });
 }
 @Override protected void onDestroy(){if(busy)Engine.cancelDesktop();worker.shutdown();if(retainedFile!=null)retainedFile.delete();if(ownedMember!=null)ownedMember.delete();super.onDestroy();}
 private class Graph extends View{
  final Inspector.Result r;final TextView detail;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int selected=-1;
  Graph(Inspector.Result data,TextView t){super(MainActivity.this);r=data;detail=t;setContentDescription("Entropy graph, 0 to 8 bits per byte");}
  @Override protected void onDraw(Canvas c){float l=dp(30),right=getWidth()-dp(8),top=dp(12),bottom=getHeight()-dp(20);p.setTextSize(dp(12));for(int i=0;i<=8;i+=2){float y=bottom-(bottom-top)*i/8f;p.setColor(0xff58646f);c.drawLine(l,y,right,y,p);p.setColor(0xffd3dce5);c.drawText(""+i,dp(7),y,p);}if(r.blocks.length==0)return;float w=(right-l)/r.blocks.length;p.setColor(0xff80cbc4);for(int i=0;i<r.blocks.length;i++)c.drawRect(l+w*i,bottom-(bottom-top)*(float)r.blocks[i]/8,l+w*(i+1),bottom,p);if(selected>=0){p.setColor(0xffffce75);float x=l+w*(selected+.5f);c.drawLine(x,top,x,bottom,p);}}
  @Override public boolean onTouchEvent(MotionEvent e){if(r.blocks.length==0)return false;if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){selected=Math.max(0,Math.min(r.blocks.length-1,(int)((e.getX()-dp(30))*r.blocks.length/Math.max(1,getWidth()-dp(38)))));long block=Math.max(4096,(r.size+255)/256);detail.setText(String.format(Locale.ROOT,"Offset 0x%X • %.4f bits/byte",block*selected,r.blocks[selected]));invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
