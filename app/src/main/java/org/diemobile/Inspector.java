// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
final class Inspector {
    static class Result {
        long size; String type, sha256, hex, strings; double entropy; double[] blocks;
    }
    static String identify(byte[] b,int n) {
        if(n>=4 && b[0]==127 && b[1]=='E'&&b[2]=='L'&&b[3]=='F') {
            String bits=n>4?(b[4]==2?"64-bit":b[4]==1?"32-bit":"unknown class"):"unknown class";
            int m=n>=20?(b[5]==2?((b[18]&255)<<8)|(b[19]&255):((b[19]&255)<<8)|(b[18]&255)):-1;
            String arch=m==183?"AArch64":m==40?"ARM":m==62?"x86-64":m==3?"x86":"machine "+m;
            return "ELF / "+bits+" / "+arch;
        }
        if(n>=4&&b[0]=='d'&&b[1]=='e'&&b[2]=='x'&&b[3]=='\n')return "DEX bytecode";
        if(n>=4&&b[0]=='P'&&b[1]=='K'&&((b[2]==3&&b[3]==4)||(b[2]==5&&b[3]==6)||(b[2]==7&&b[3]==8)))return "ZIP container";
        if(n>=2&&b[0]=='M'&&b[1]=='Z')return "MZ executable candidate";
        if(n>=4&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F')return "PDF";
        if(n>=4&&(b[0]&255)==0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return "PNG";
        return "Unknown / raw data";
    }
    static double entropy(long[] counts,long n){ if(n==0)return 0; double h=0;for(long c:counts)if(c>0){double p=(double)c/n;h-=p*Math.log(p)/Math.log(2);}return h; }
    static Result scan(File f,int minString) throws Exception {
        return scan(f,minString,()->false);
    }
    static Result scan(File f,int minString,java.util.function.BooleanSupplier cancelled) throws Exception {
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Inspection cancelled");
        Result r=new Result(); r.size=f.length(); MessageDigest md=MessageDigest.getInstance("SHA-256");
        long[] counts=new long[256]; int blockSize=(int)Math.max(4096,Math.min(Integer.MAX_VALUE,(r.size+255)/256));
        long[] hist=new long[256]; int used=0; ArrayList<Double> blocks=new ArrayList<>();
        byte[] buf=new byte[65536], head=new byte[4096]; int hlen=0; long pos=0,start=0; StringBuilder run=new StringBuilder(),strings=new StringBuilder();
        try(InputStream in=new BufferedInputStream(new FileInputStream(f))){int n;while((n=in.read(buf))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("Inspection cancelled");md.update(buf,0,n);int copy=Math.min(n,head.length-hlen);System.arraycopy(buf,0,head,hlen,copy);hlen+=copy;
            for(int i=0;i<n;i++,pos++){int v=buf[i]&255;counts[v]++;hist[v]++;if(++used==blockSize){blocks.add(entropy(hist,used));Arrays.fill(hist,0);used=0;}
                if(v>=32&&v<=126){if(run.length()==0)start=pos;if(run.length()<4096)run.append((char)v);}else {append(strings,run,start,minString);run.setLength(0);}
            }
        }}if(cancelled.getAsBoolean())throw new InterruptedIOException("Inspection cancelled");append(strings,run,start,minString);if(used>0)blocks.add(entropy(hist,used));r.entropy=entropy(counts,pos);r.blocks=new double[blocks.size()];for(int i=0;i<blocks.size();i++)r.blocks[i]=blocks.get(i);r.strings=strings.toString();r.type=identify(head,hlen);r.sha256=toHex(md.digest());
        StringBuilder hex=new StringBuilder();for(int i=0;i<hlen;i+=16){hex.append(String.format(Locale.ROOT,"%08X  ",i));for(int j=0;j<16;j++)hex.append(i+j<hlen?String.format(Locale.ROOT,"%02X ",head[i+j]&255):"   ");hex.append(" ");for(int j=i;j<Math.min(i+16,hlen);j++){int v=head[j]&255;hex.append(v>=32&&v<=126?(char)v:'.');}hex.append('\n');}r.hex=hex.toString();return r;
    }
    static void append(StringBuilder out,StringBuilder run,long offset,int min){if(run.length()>=min&&out.length()<200000)out.append(String.format(Locale.ROOT,"%08X  ",offset)).append(run).append('\n');}
    static String toHex(byte[] b){StringBuilder sb=new StringBuilder();for(byte v:b)sb.append(String.format(Locale.ROOT,"%02x",v&255));return sb.toString();}
}
