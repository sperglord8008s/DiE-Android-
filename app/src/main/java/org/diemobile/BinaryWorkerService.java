// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Descriptor-only isolated UID. It never receives an input/output filesystem path. */
public final class BinaryWorkerService extends Service {
    static final int RUN=1,CANCEL=2;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String activeToken;
    private boolean isolated;
    private Messenger messenger;
    @Override public void onCreate(){super.onCreate();isolated=android.os.Process.myUid()!=getApplicationInfo().uid;
        messenger=new Messenger(new Handler(Looper.getMainLooper(),message->{
            Bundle data=message.getData();String token=data.getString("token");
            if(message.what==CANCEL){if(isolated&&token!=null&&token.equals(activeToken))android.os.Process.killProcess(android.os.Process.myPid());return true;}
            if(message.what!=RUN)return false;
            ParcelFileDescriptor input=data.getParcelable("input"),output=data.getParcelable("output");
            if(input==null||output==null||token==null){close(input);close(output);return true;}
            String engine=data.getString("engine","binwalk");
            String rejection=!isolated?"The binary worker does not have an isolated UID; in-process fallback is prohibited":!engine.equals("binwalk")&&!engine.equals("mpv")?"Unsupported worker engine":activeToken!=null?"Binary worker is already serving another request":null;
            if(rejection==null)activeToken=token;
            // A rejected second client must not queue behind a slow first job.
            // Its small error envelope fits a fresh pipe and needs no native call.
            if(rejection!=null)execute(token,input,output,engine,rejection);else worker.execute(()->execute(token,input,output,engine,null));return true;
        }));
    }
    private void execute(String token,ParcelFileDescriptor input,ParcelFileDescriptor output,String engine,String rejection){
        try(ParcelFileDescriptor source=input;OutputStream target=new ParcelFileDescriptor.AutoCloseOutputStream(output)){
            String result;
            try{
                if(rejection!=null)throw new IOException(rejection);
                if(engine.equals("mpv")){
                    if(!MpvEngine.AVAILABLE)throw new IOException("MPV native library unavailable: "+MpvEngine.ERROR);
                    result=MpvEngine.scanFd(source.getFd());
                }else{
                    if(!BinwalkEngine.AVAILABLE)throw new IOException("Binwalk native library unavailable: "+BinwalkEngine.ERROR);
                    result=BinwalkEngine.scanFd(source.getFd());
                }
                if(result==null)throw new IOException("Native parser returned no complete report");
            }catch(Exception|LinkageError failure){result=new JSONObject().put("ok",false).put("error",failure.toString()).toString();}
            target.write(("{\"workerUid\":"+android.os.Process.myUid()+",\"applicationUid\":"+getApplicationInfo().uid+",\"isolatedUid\":"+isolated+",\"engine\":"+JSONObject.quote(engine)+",\"report\":").getBytes(StandardCharsets.UTF_8));
            target.write(result.getBytes(StandardCharsets.UTF_8));target.write('}');
        }catch(Exception ignored){/* Pipe closure/crash is an explicit incomplete-report failure in the caller. */}
        finally{new Handler(Looper.getMainLooper()).post(()->{if(token.equals(activeToken))activeToken=null;});}
    }
    private static void close(ParcelFileDescriptor value){if(value!=null)try{value.close();}catch(IOException ignored){}}
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
    @Override public boolean onUnbind(Intent intent){if(isolated)android.os.Process.killProcess(android.os.Process.myPid());return false;}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
