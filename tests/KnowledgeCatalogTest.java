// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual canonical data/RE2/J plus upstream shared matching and complete exports. */
public final class KnowledgeCatalogTest {
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static NameMatcher.Rule rule(KnowledgeCatalog catalog,int id) {
        return catalog.matcher.rules.stream().filter(value->value.id==id).findFirst().orElseThrow();
    }
    public static void main(String[] args) throws Exception {
        File source=new File(args[0]);KnowledgeCatalog catalog;
        try(InputStream input=new FileInputStream(source)) { catalog=KnowledgeCatalog.read(input,()->false); }
        require(catalog.matcher.rules.size()==3053&&catalog.matcher.unsupported.isEmpty(),"Pinned canonical rule inventory/regex support differs");
        require(catalog.matcher.rules.stream().filter(value->value.regex).count()==123,"Canonical regex inventory differs");
        require(catalog.details.length()==2934,"Effective metadata locale payloads omitted");
        JSONObject fixtures=new JSONObject(Files.readString(Path.of(args[1])));JSONArray cases=fixtures.getJSONArray("cases");
        for(int i=0;i<cases.length();i++) {
            JSONObject test=cases.getJSONObject(i);NameMatcher matcher=new NameMatcher(test.getJSONArray("rules"),()->false);
            NameMatcher.Match hit=matcher.match(test.getInt("type"),test.getString("input"),test.optBoolean("useRegex",true),()->false);
            require(test.isNull("expected")?hit==null:hit!=null&&hit.rule.id==test.getInt("expected"),"Upstream matching fixture differs: "+test.getString("name"));
        }
        require(catalog.matcher.match(0,"libflutter.so",()->false).rule.id==71,"Real exact native rule differs");
        NameMatcher.Match torrent=catalog.matcher.match(0,"libjlibtorrent-1.2.3.so",()->false);
        require(torrent!=null&&torrent.rule.regex&&torrent.rule.name.equals("libjlibtorrent-(.*).so"),"Real native regex rule not used");
        require(catalog.matcher.match(1,"com.qihoo360.replugin.component.service.server.PluginPitServiceP1",()->false)!=null,"Real service regex missing");
        require(catalog.matcher.match(5,"androidx.preference",()->false)!=null,"DEX package matcher absent");
        require(catalog.matcher.match(6,"com.google.android.trichromelibrary",()->false)!=null,"Static library matcher absent");
        require(catalog.matcher.match(9,"com.baidu.action.SOFIRE.VIEW",()->false)!=null,"Intent action matcher absent");
        require(catalog.english(rule(catalog,505)).length()==0,"No-detail matcher borrowed another description");
        EvidenceRows rows=catalog.rows(-1,false);require(rows.size()==3053,"Catalog viewer dropped records");
        EvidenceRows.Page second=rows.select("",()->false).page(1,()->false);require(second.rows.get(0).index==50,"Catalog page indexing differs");
        EvidenceRows.Row flutter=null;
        for(int i=0;i<rows.size();i++) { EvidenceRows.Row row=rows.row(i,()->false);if(row.json().getInt("matcherId")==71) { flutter=row;break; } }
        require(flutter!=null&&flutter.physicalOffset==null,"Rule assigned a fictitious selected-file range");
        StringWriter output=new StringWriter();rows.writeSelectedRow(output,flutter,()->false);
        JSONObject exported=new JSONObject(output.toString());
        require(exported.getJSONObject("fullOriginalRule").getString("name").equals("libflutter.so"),"Displayed label replaced original rule name in export");
        require(exported.getJSONObject("effectiveDetails").getJSONArray("data").length()>=2,"Original locale payloads flattened");
        EvidenceRows group=catalog.rows(-1,false,flutter.json().getString("uuid"));require(group.size()>0&&group.size()<rows.size(),"Library UUID relationship not scoped");
        File directory=new File(args[2],"knowledge-snapshots");
        try(SdkCatalogBackend backend=new SdkCatalogBackend(()->new FileInputStream(source),directory)) {
            String initial=backend.initialRequest(()->false);require(backend.open(initial,()->false).size()==3053,"Owned catalog provider incomplete");
            require(!backend.byteSource().equals(source)&&backend.provenance().getString("catalogSha256").equals(catalog.sha256),"Catalog source provenance/ownership missing");
            require(backend.roots(initial,()->false).size()==12,"Canonical type/diagnostic roots missing");
        }
        require(Objects.requireNonNull(directory.list()).length==0,"Closed catalog snapshot leaked");
        try(SdkCatalogBackend backend=new SdkCatalogBackend(()->new FileInputStream(source),directory)) {
            try { backend.open(backend.initialRequest(()->false),()->true);throw new AssertionError("Catalog acquisition ignored cancellation"); }
            catch(InterruptedIOException expected) {}
        }
        require(Objects.requireNonNull(directory.list()).length==0,"Cancelled catalog copy leaked");
        JSONArray unsupported=new JSONArray().put(new JSONObject().put("id",1).put("type",0).put("priority",1).put("name","(?=foo)").put("isRegexRule",true));
        NameMatcher invalid=new NameMatcher(unsupported,()->false);
        require(invalid.unsupported.size()==1&&invalid.match(0,"foo",()->false)==null&&invalid.match(0,"(?=foo)",()->false)!=null,"Unsupported regex diagnostic/literal exact behavior differs");
        JSONArray stress=new JSONArray().put(new JSONObject().put("id",1).put("type",0).put("priority",1).put("name","(a+)+b").put("isRegexRule",true));
        NameMatcher linear=new NameMatcher(stress,()->false);long before=System.nanoTime();
        require(linear.match(0,"a".repeat(16000),()->false)==null,"Nested repetition unexpectedly matched");
        require(System.nanoTime()-before<5_000_000_000L,"Linear-time matcher regressed to backtracking");
        try { linear.match(0,"a".repeat(16385),()->false);throw new AssertionError("Name length budget ignored"); } catch(IOException expected) {}
        try { linear.match(0,"abc",()->true);throw new AssertionError("Rule matching ignored cancellation"); } catch(InterruptedIOException expected) {}
        System.out.println("Knowledge fixtures passed: actual 3,053-rule canonical source, all 123 regex compiled, 19 upstream contract cases, real native/component/DEX/static/action rules, all locale/no-detail semantics, complete original exports, UUID paging, ownership/cancellation/unsupported/length and non-backtracking boundaries.");
    }
}
