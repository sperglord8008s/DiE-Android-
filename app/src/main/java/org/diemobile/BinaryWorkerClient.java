// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Pipe reports avoid Binder's transaction-size limit; cancellation targets one job token. */
final class BinaryWorkerClient implements BinaryRunner {
    private static final long TIMEOUT_MS=45000;
    private final Context context;
    private final String engine;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed=new AtomicBoolean();
    BinaryWorkerClient(Context context){this(context,"binwalk");}
    BinaryWorkerClient(Context context,String engine){
        if(!engine.equals("binwalk")&&!engine.equals("photorec")&&!engine.equals("mpv")&&!engine.equals("resources")&&!engine.equals("hashes"))throw new IllegalArgumentException("Unknown binary worker engine");
        this.context=context.getApplicationContext();this.engine=engine;
    }
    @Override public String scan(File input,BooleanSupplier stop)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Binary worker client must run off the UI thread");
        EvidenceRows.check(stop);if(closed.get())throw new IOException("Binary worker client closed");
        String token=UUID.randomUUID().toString();AtomicReference<Messenger> remote=new AtomicReference<>();AtomicReference<Exception> failure=new AtomicReference<>();CountDownLatch connected=new CountDownLatch(1);AtomicBoolean bound=new AtomicBoolean(),finished=new AtomicBoolean();
        long started=SystemClock.elapsedRealtime();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){remote.set(new Messenger(binder));connected.countDown();}
            public void onServiceDisconnected(ComponentName name){failure.compareAndSet(null,new IOException("Isolated binary worker disconnected before completing its report"));connected.countDown();}
            @Override public void onNullBinding(ComponentName name){failure.set(new IOException("Binary worker returned no binding"));connected.countDown();}
            @Override public void onBindingDied(ComponentName name){failure.set(new IOException("Binary worker binding died"));connected.countDown();}
        };
        Runnable unbind=()->{if(bound.compareAndSet(true,false))try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}};
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
        watchdog.scheduleAtFixedRate(()->{
            if(finished.get())return;
            if(stop.getAsBoolean()||closed.get()||SystemClock.elapsedRealtime()-started>TIMEOUT_MS){
                failure.compareAndSet(null,stop.getAsBoolean()||closed.get()?new InterruptedIOException("Binary analysis cancelled"):new IOException("Binary worker exceeded the 45-second request deadline"));
                Messenger service=remote.get();if(service!=null)try{Message cancel=Message.obtain(null,BinaryWorkerService.CANCEL);cancel.getData().putString("token",token);service.send(cancel);}catch(RemoteException ignored){}
                main.post(unbind);connected.countDown();
            }
        },0,100,TimeUnit.MILLISECONDS);
        main.post(()->{if(finished.get()||failure.get()!=null)return;try{boolean result=context.bindService(new Intent(context,BinaryWorkerService.class),connection,Context.BIND_AUTO_CREATE);bound.set(result);if(!result){failure.set(new IOException("Unable to bind isolated binary worker"));connected.countDown();}}catch(RuntimeException error){failure.set(error);connected.countDown();}});
        ParcelFileDescriptor[] pipe=null;
        try{
            while(!connected.await(100,TimeUnit.MILLISECONDS)){EvidenceRows.check(stop);if(failure.get()!=null)throw failure.get();}
            if(failure.get()!=null)throw failure.get();if(remote.get()==null)throw new IOException("Isolated worker unavailable");
            pipe=ParcelFileDescriptor.createPipe();
            try(ParcelFileDescriptor source=ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY)){
                Message request=Message.obtain(null,BinaryWorkerService.RUN);Bundle arguments=request.getData();arguments.putString("token",token);arguments.putString("engine",engine);arguments.putParcelable("input",source);arguments.putParcelable("output",pipe[1]);remote.get().send(request);
            }
            pipe[1].close();pipe[1]=null;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream reader=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){pipe[0]=null;byte[] block=new byte[65536];int count;while((count=reader.read(block))>=0){if(failure.get()!=null)throw failure.get();EvidenceRows.check(stop);if(count>32*1024*1024-bytes.size())throw new IOException("Binary worker report exceeds 32 MiB");bytes.write(block,0,count);}}
            if(failure.get()!=null)throw failure.get();EvidenceRows.check(stop);
            JSONObject envelope;
            try{envelope=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));}catch(JSONException error){throw new IOException("Isolated worker exited or returned an incomplete report",error);}
            if(!envelope.getBoolean("isolatedUid"))throw new IOException("Worker did not confirm UID isolation");
            if(!engine.equals(envelope.getString("engine")))throw new IOException("Worker response belongs to a different engine");
            JSONObject report=envelope.getJSONObject("report");envelope.remove("report");report.put("worker",envelope.put("requestDeadlineMs",TIMEOUT_MS));return report.toString();
        }finally{
            finished.set(true);watchdog.shutdownNow();if(pipe!=null)for(ParcelFileDescriptor descriptor:pipe)if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}main.post(unbind);
        }
    }
    @Override public void close(){closed.set(true);}
}
