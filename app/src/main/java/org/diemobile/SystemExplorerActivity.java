// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only navigator. Declared services are not a list of live services. */
public final class SystemExplorerActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final List<Entry> rows=new ArrayList<>();
 private LinearLayout root;
 private TextView heading,detail;
 private EditText search;
 private ListView list;
 private int generation;
 private static class Entry {
  final String label,description;final Runnable open;
  Entry(String l,String d,Runnable r){label=l;description=d;open=r;}
  public String toString(){return label+"\n"+description;}
 }
 private int dp(int i){return Math.round(i*getResources().getDisplayMetrics().density);}
 private Button btn(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackgroundColor(0xff15191e);
  heading=new TextView(this);heading.setTextSize(18);root.addView(heading);
  detail=new TextView(this);detail.setTextSize(12);root.addView(detail);
  LinearLayout nav=new LinearLayout(this);for(String label:new String[]{"User apps","System apps","Files","Services"}){final String l=label;nav.addView(btn(l,()->{if(l.equals("Files"))browse(new File("/"));else packages(l.equals("Services")?2:l.equals("System apps")?1:0);}),new LinearLayout.LayoutParams(0,-2,1));}root.addView(nav);
  search=new EditText(this);search.setSingleLine();search.setHint("Filter by name or path");root.addView(search);
  list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(android.text.Editable e){}});
  packages(0);
 }
 private void render(){
  String q=search.getText().toString().toLowerCase(Locale.ROOT);ArrayList<Entry> visible=new ArrayList<>();
  for(Entry e:rows)if(e.toString().toLowerCase(Locale.ROOT).contains(q))visible.add(e);
  list.setAdapter(new ArrayAdapter<Entry>(this,android.R.layout.simple_list_item_1,visible));
  list.setOnItemClickListener((p,v,pos,id)->visible.get(pos).open.run());
 }
 private int begin(String title,String desc){heading.setText(title);detail.setText(desc);rows.clear();search.setText("");render();return ++generation;}
 private void finishRows(int ticket,List<Entry> result,String msg){runOnUiThread(()->{if(isDestroyed()||ticket!=generation)return;rows.clear();rows.addAll(result);detail.setText(msg);render();});}
 private void packages(int mode){
  final int ticket=begin(mode==2?"Declared Android services":mode==1?"System packages":"User packages","Reading local PackageManager inventory…");
  worker.execute(()->{
   ArrayList<Entry> entries=new ArrayList<>();
   try{
    PackageManager pm=getPackageManager();List<ApplicationInfo> apps=pm.getInstalledApplications(0);apps.sort(Comparator.comparing(a->a.packageName));
    for(ApplicationInfo a:apps){
     boolean system=(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0||(a.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
     if(mode!=2&&system!=(mode==1))continue;
     String name=String.valueOf(pm.getApplicationLabel(a));String pkg=a.packageName;
     if(mode!=2){entries.add(new Entry(name,pkg+" • "+(system?"system":"user")+" • enabled="+a.enabled,()->packageDetails(pkg)));continue;}
     try{PackageInfo pi=pm.getPackageInfo(pkg,PackageManager.GET_SERVICES|PackageManager.MATCH_DISABLED_COMPONENTS);if(pi.services==null)continue;
      for(ServiceInfo svc:pi.services){String info=pkg+" • exported="+svc.exported+" • enabled="+svc.enabled+" • process="+svc.processName;entries.add(new Entry(svc.name,info,()->new AlertDialog.Builder(this).setTitle(svc.name).setMessage(info+"\nPermission: "+svc.permission+"\n\nA service is a manifest component for started or bound work. This is a declaration, not evidence that the service is running. Exported services may accept calls from other apps subject to permissions and OS rules.").setNeutralButton("Package",(d,w)->packageDetails(pkg)).setPositiveButton("Close",null).show()));}
     }catch(Exception ignored){}
     if(entries.size()>20000)break;
    }
    finishRows(ticket,entries,entries.size()+" entries • current Android user/profile and package visibility • no live-service claim");
   }catch(Exception e){finishRows(ticket,entries,"Inventory error: "+e);}
  });
 }
 private void packageDetails(String pkg){
  final int ticket=begin(pkg,"Reading package metadata…");worker.execute(()->{
   ArrayList<Entry> entries=new ArrayList<>();
   try{PackageManager pm=getPackageManager();PackageInfo info=pm.getPackageInfo(pkg,PackageManager.GET_SERVICES|PackageManager.GET_ACTIVITIES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);ApplicationInfo a=info.applicationInfo;
    String meta="Package: "+pkg+"\nVersion: "+info.versionName+"\nUID: "+a.uid+" • min/target SDK: "+a.minSdkVersion+"/"+a.targetSdkVersion+"\nData directory: "+a.dataDir+"\nNative library directory: "+a.nativeLibraryDir+"\n\nUID is the Android/Linux security identity. Paths identify configured directories, not permission to read them. System-app status is not a trust guarantee.";
    entries.add(new Entry("Package identity","Tap for technical details",()->new AlertDialog.Builder(this).setTitle(pkg).setMessage(meta).setPositiveButton("Close",null).show()));
    entries.add(new Entry("Base APK",a.sourceDir,()->select(new File(a.sourceDir))));
    if(a.splitSourceDirs!=null)for(String p:a.splitSourceDirs)entries.add(new Entry("Split APK",p,()->select(new File(p))));
    if(a.nativeLibraryDir!=null)entries.add(new Entry("Native library directory",a.nativeLibraryDir,()->browse(new File(a.nativeLibraryDir))));
    if(a.dataDir!=null)entries.add(new Entry("Data directory (may be inaccessible)",a.dataDir,()->browse(new File(a.dataDir))));
    for(ComponentInfo[] arr:new ComponentInfo[][]{info.activities,info.services,info.receivers,info.providers})if(arr!=null)for(ComponentInfo c:arr){String desc=c.getClass().getSimpleName()+" • exported="+c.exported+" • enabled="+c.enabled+" • process="+c.processName;entries.add(new Entry(c.name,desc,()->new AlertDialog.Builder(this).setTitle(c.name).setMessage(desc+"\n\nManifest declaration. An exported component is potentially addressable by other applications subject to platform restrictions and permissions.").setPositiveButton("Close",null).show()));}
    if(info.requestedPermissions!=null)for(String p:info.requestedPermissions)entries.add(new Entry(p,"Requested permission",()->new AlertDialog.Builder(this).setTitle(p).setMessage("Declared permission, not proof of use. Analyze the base APK for additional permission interpretation.").setPositiveButton("Close",null).show()));
    finishRows(ticket,entries,meta.split("\n\n")[0]);
   }catch(Exception e){finishRows(ticket,entries,"Package metadata unavailable: "+e);}
  });
 }
 private void browse(File dir){
  final int ticket=begin(dir.getAbsolutePath(),"Read-only filesystem • only OS-permitted paths");
  worker.execute(()->{ArrayList<Entry> entries=new ArrayList<>();File parent=dir.getParentFile();if(parent!=null)entries.add(new Entry("..",parent.getAbsolutePath(),()->browse(parent)));
   try{File[] children=dir.listFiles();if(children==null){finishRows(ticket,entries,"Directory inaccessible or unavailable. Android sandbox/SELinux may restrict listing.");return;}
    Arrays.sort(children,Comparator.comparing((File f)->!f.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));
    for(int i=0;i<Math.min(children.length,20000);i++){File f=children[i];boolean d=f.isDirectory();entries.add(new Entry(f.getName(),(d?"Directory":f.length()+" bytes")+" • "+(f.canRead()?"readable":"restricted"),()->{if(d)browse(f);else select(f);}));}
    finishRows(ticket,entries,children.length+" directory entries"+(children.length>20000?" • display capped":"")+" • read-only");
   }catch(Exception e){finishRows(ticket,entries,"Filesystem error: "+e);}
  });
 }
 private void select(File file){
  // /proc, /sys and device nodes may be non-regular or never-ending streams.
  try{android.system.StructStat st=android.system.Os.stat(file.getAbsolutePath());if(!android.system.OsConstants.S_ISREG(st.st_mode)||!file.canRead()){Toast.makeText(this,"Only readable regular files can be scanned",Toast.LENGTH_LONG).show();return;}
   String path=file.getCanonicalPath();if(path.startsWith("/proc/")||path.startsWith("/sys/")||path.startsWith("/dev/")){Toast.makeText(this,"Virtual kernel files are excluded from binary scanning",Toast.LENGTH_LONG).show();return;}
   setResult(RESULT_OK,new Intent().putExtra("path",path));finish();
  }catch(Exception e){Toast.makeText(this,"Cannot access: "+e.getMessage(),Toast.LENGTH_LONG).show();}
 }
 @Override public void onDestroy(){generation++;worker.shutdown();super.onDestroy();}
}
