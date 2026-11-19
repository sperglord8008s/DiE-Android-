// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.re2j.Matcher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Differential proof against unfiltered RE2 searches, plus realistic multidex-size workloads. */
public final class TrackerPrefilterTest {
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static TrackerCatalog catalog(List<String> patterns) throws Exception {
        JSONObject source=new JSONObject();int index=0;
        for(String pattern:patterns) source.put(Integer.toString(index++),new JSONObject().put("name","Synthetic rule").put("code_signature",pattern));
        String text=new JSONObject().put("trackers",source).toString();
        return TrackerCatalog.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);
    }
    private static String literal(String text) {
        StringBuilder pattern=new StringBuilder();
        for(char unit:text.toCharArray()) { if("\\.^$*+?()[]{}|".indexOf(unit)>=0) pattern.append('\\');pattern.append(unit); }
        return pattern.toString();
    }
    private static void compare(TrackerCatalog catalog,Collection<String> descriptors) throws Exception {
        TrackerCatalog.Result actual=catalog.match(descriptors,()->false);require(actual.complete(),"Differential search became partial: "+actual.limit);
        List<String> ordered=new ArrayList<>(new TreeSet<>(descriptors));
        for(int i=0;i<catalog.rules.size();i++) {
            TrackerCatalog.Rule rule=catalog.rules.get(i);TrackerCatalog.Evaluation observed=actual.evaluations.get(i);
            require(rule.pattern!=null,"Differential rule outside the compared syntax: "+rule.signature);String expected=null;int start=-1,end=-1;
            // This deliberately simple independent oracle performs every search
            // in rule-first order, without any literal extraction or index.
            for(String descriptor:ordered) {
                String input=descriptor.substring(1,descriptor.length()-1);Matcher match=rule.pattern.matcher(input);
                if(match.find()) { expected=descriptor;start=match.start();end=match.end();break; }
            }
            require(expected==null?!observed.status.equals("matched"):observed.status.equals("matched")&&observed.descriptor.equals(expected)&&observed.start==start&&observed.end==end,
                "Prefilter changed a positive, negative, first witness or exact span: "+rule.signature);
        }
    }
    public static void main(String[] args) throws Exception {
        List<String> edgePatterns=Arrays.asList("prefixsuffix","suffix","fixsuffix","suffix|prefix.suffix","foo\\.bar","foo.bar","left|right","(?:)","^only$",".*ab","word/汉字库");
        TrackerCatalog edges=catalog(edgePatterns);
        compare(edges,Arrays.asList("Lprefixsuffix;","Lprefix/suffix;","Lfoo.bar;","Lfoo/bar;","Lonly;","Lxx/right;","Lxx/left;","Lword/汉字库;","L😀/ab;"));
        require(edges.prefilter.ruleCount>0&&edges.prefilter.ruleCount<edges.rules.size(),"Both indexed and fallback rules were not exercised");
        TrackerCatalog falsePositive=catalog(Collections.singletonList("com.vendor.sdk"));
        TrackerCatalog.Result miss=falsePositive.match(Collections.singleton("Lunrelated/vendor/thing;"),()->false);
        require(miss.complete()&&miss.hits==0&&miss.comparisons==1,"An atom candidate was reported as a regex match");
        Random random=new Random(0xD1E2026L);
        String[] pieces={"alpha","beta","gamma","abcd","bcd","suffix","a+b","foo.bar","data$inner","x\\y","slash/name","do|or","汉字库"};
        List<String> patterns=new ArrayList<>(edgePatterns),names=new ArrayList<>();
        for(int i=0;i<180;i++) {
            String first=literal(pieces[random.nextInt(pieces.length)]),second=literal(pieces[random.nextInt(pieces.length)]);
            switch(i%5) {
                case 0: patterns.add(first+"."+second);break;
                case 1: patterns.add(first+"|"+second);break;
                case 2: patterns.add("("+first+")+/"+second);break;
                case 3: patterns.add("[a-z]+/"+second);break;
                default: patterns.add("^"+first+"$");break;
            }
        }
        for(String piece:pieces) names.add("L"+piece+";");
        for(int i=0;i<2000;i++) names.add("L"+(i%7==0?"😀/":"")+pieces[random.nextInt(pieces.length)]+(i%3==0?"/":".")+pieces[random.nextInt(pieces.length)]+";");
        compare(catalog(patterns),names);
        List<String> scalePatterns=new ArrayList<>(),scaleNames=new ArrayList<>();
        for(int i=0;i<400;i++) scalePatterns.add(String.format(Locale.ROOT,"com.vendor%03d.sdk.|org.partner%03d.",i,i));
        for(int i=0;i<30000;i++) scaleNames.add(String.format(Locale.ROOT,"Lapp/ordinary/Indexed%05d;",i));
        scaleNames.add("Laaa/vendor287/unrelated;");scaleNames.add("Lcom/vendor287/sdk/Init;");scaleNames.add("Lorg/partner003/Collector;");
        TrackerCatalog scale=catalog(scalePatterns);long began=System.nanoTime();
        TrackerCatalog.Result result=scale.match(scaleNames,()->false,new TrackerCatalog.Limits(8,128L*1024*1024,1000000000,10000000000L));
        require(scale.prefilter.ruleCount==400&&result.complete()&&result.hits==2&&result.comparisons==3,"Large class set did not retain complete regex results within an eight-comparison allowance: "+result.limit+" comparisons="+result.comparisons);
        require(result.prefilterUnits>700000&&result.prefilterUnits<900000&&result.prefilterSteps>result.prefilterUnits,"Actual shared-index coverage/work was not recorded");
        TrackerCatalog.Result exhausted=scale.match(scaleNames,()->false,new TrackerCatalog.Limits(8,0,1000000000,10000000000L));
        require(!exhausted.complete()&&exhausted.unsearched==400&&exhausted.hits==0,"Incomplete index traversal was turned into negative results");
        try { scale.prefilter.scan("ordinary",()->true,System.nanoTime(),1000000000,1000,1000);throw new AssertionError("Shared index ignored cancellation"); }
        catch(InterruptedIOException expected) {}
        System.out.println("Tracker prefilter passed: 191 mixed indexed/fallback rules versus unfiltered regex, overlaps/suffix outputs/alternatives/escaped literals/Unicode/first witnesses/exact spans; 400 rules across "+scaleNames.size()+" descriptors used "+result.comparisons+" actual regex searches, "+result.prefilterUnits+" shared-index units, "+result.prefilterSteps+" work steps in "+((System.nanoTime()-began)/1000000)+" ms; exhausted/cancelled searches retain unknown coverage.");
    }
}
