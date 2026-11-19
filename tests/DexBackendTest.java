// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Provider relationships and snapshot ownership, using real DEX and ZIP bytes. */
public final class DexBackendTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static InvestigationBackend.Link link(List<InvestigationBackend.Link> values, String label) {
        return values.stream().filter(value -> value.label.contains(label)).findFirst().orElseThrow(() -> new AssertionError("Missing link " + label));
    }
    private static EvidenceRows.Row row(EvidenceRows rows, String name) throws Exception {
        for (int i = 0; i < rows.size(); i++) { EvidenceRows.Row row = rows.row(i, () -> false); if (row.name.contains(name)) return row; }
        throw new AssertionError("Missing row " + name);
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); File input = directory.resolve("provider.dex").toFile(); Files.write(input.toPath(), DexFixtureFactory.rich());
        DexBackend backend = new DexBackend(input, directory.resolve("members").toFile(), "provider.dex", "", input.length());
        String root = backend.initialRequest(() -> false); EvidenceRows classes = backend.open(root, () -> false);
        String urlsRequest = link(backend.roots(root,() -> false),"Strings: URLs").request;
        EvidenceRows urls = backend.open(urlsRequest,() -> false); EvidenceRows.Row endpoint = row(urls,DexFixtureFactory.ENDPOINT);
        require(endpoint.json().getJSONArray("classificationMatches").length() > 0 && urls.metadata().getJSONObject("classifier").getString("rulesSha256").length() == 64,"DEX classifications/provenance missing");
        InvestigationBackend.Link stringUses = link(backend.related(urlsRequest,urls,endpoint,() -> false),"encoded uses");
        require(backend.open(stringUses.request,() -> false).size() == 1,"Classified string did not retain encoded bytecode use");
        InvestigationBackend.Link methods = link(backend.related(root, classes, row(classes, "研究"), () -> false), "Declared methods");
        EvidenceRows methodRows = backend.open(methods.request, () -> false);
        InvestigationBackend.Link code = link(backend.related(methods.request, methodRows, row(methodRows, "->inspect()V"), () -> false), "Method bytecode");
        EvidenceRows body = backend.open(code.request, () -> false);
        InvestigationBackend.Link elements = link(backend.related(code.request, body, row(body, "array-payload"), () -> false), "All array");
        EvidenceRows array = backend.open(elements.request, () -> false); require(array.size() == 80, "Array table lost values");
        require(link(backend.related(elements.request, array, array.row(0, () -> false), () -> false), "Method bytecode") != null, "Payload cannot return to method");
        InvestigationBackend.Link pool = link(backend.related(code.request, body, row(body, "invoke-static"), () -> false), "pool record");
        EvidenceRows refs = backend.open(pool.request, () -> false); require(pool.row < refs.size(), "Reference index is not navigable");
        InvestigationBackend.Link uses = link(backend.related(pool.request, refs, refs.row(pool.row, () -> false), () -> false), "encoded uses");
        EvidenceRows callers = backend.open(uses.request, () -> false); require(callers.size() == 1, "Reference caller missing");
        require(link(backend.related(uses.request, callers, callers.row(0, () -> false), () -> false), "Method bytecode").row > 0, "Caller does not retain instruction index");
        StringWriter smali = new StringWriter(); backend.writeText(code.request, smali, () -> false); require(smali.toString().contains(".catchall"), "Upstream method smali lost handlers");
        backend.close(); require(input.isFile(), "Closing a raw input deleted borrowed bytes");

        String unicodePrefix = "\0é😀\uD800 "; File mutf = directory.resolve("classified-mutf.dex").toFile(); Files.write(mutf.toPath(),DexFixtureFactory.literal(unicodePrefix+DexFixtureFactory.ENDPOINT));
        backend = new DexBackend(mutf,directory.resolve("members").toFile(),"classified-mutf.dex","",mutf.length());
        String mutfRoot = backend.initialRequest(() -> false); backend.open(mutfRoot,() -> false);
        EvidenceRows classified = backend.open(link(backend.roots(mutfRoot,() -> false),"Strings: URLs").request,() -> false);
        JSONObject exact = row(classified,DexFixtureFactory.ENDPOINT).json(); boolean uri = false;
        for (int i = 0; i < exact.getJSONArray("classificationMatches").length(); i++) {
            JSONObject match = exact.getJSONArray("classificationMatches").getJSONObject(i); if (!match.getString("ruleId").equals("net.uri")) continue;
            uri = true; require(match.getLong("physicalMatchOffset") == exact.getLong("physicalTextOffset")+14,"MUTF-8 NUL/surrogate byte mapping mistaken for ordinary UTF-8");
            require(match.getLong("physicalMatchLengthBytes") == DexFixtureFactory.ENDPOINT.length(),"MUTF-8 URL byte extent changed");
        }
        require(uri && exact.getString("value").equals(unicodePrefix+DexFixtureFactory.ENDPOINT),"Exact classified MUTF-8 string changed"); backend.close();

        File apk = directory.resolve("provider.apk").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) {
            for (String name : Arrays.asList("assets/non-code.txt", "classes.dex", "other/extra.dex")) {
                out.putNextEntry(new ZipEntry(name)); out.write(name.endsWith(".dex") ? Files.readAllBytes(input.toPath()) : new byte[]{1}); out.closeEntry();
            }
        }
        backend = new DexBackend(apk, directory.resolve("members").toFile(), "provider.apk", "", apk.length());
        String inputs = backend.initialRequest(() -> false); EvidenceRows inventory = backend.open(inputs, () -> false);
        require(inventory.size() == 2 && inventory.row(0, () -> false).json().getInt("memberIndex") == 1, "Filename candidates use filtered positions as archive indices");
        String memberRequest = link(backend.related(inputs, inventory, inventory.row(0, () -> false), () -> false), "Inspect").request;
        backend.open(memberRequest, () -> false); File selected = backend.byteSource(); require(!selected.equals(apk) && selected.isFile(), "Member not privately staged");
        require(backend.provenance().getInt("archiveMemberIndex") == 1 && backend.provenance().getJSONObject("input").getString("sha256").length() == 64, "Member provenance missing");
        backend.open(inputs, () -> false); require(backend.byteSource().equals(apk) && backend.provenance().getString("engine").contains("ZIP"), "Parent inventory still reports child bytes");
        backend.close(); require(!selected.exists() && apk.isFile(), "Private member ownership failed");
        backend = new DexBackend(apk, directory.resolve("members").toFile(), "provider.apk", "", apk.length());
        backend.open(memberRequest, () -> false); require(link(backend.roots(memberRequest, () -> false), "Choose a DEX") != null, "Restored member loses archive root navigation"); backend.close();

        File exports = directory.resolve("strict-exports").toFile();
        try { PreparedExport.stream(exports, "invalid.txt", "text/plain", out -> out.write("\uD800"), () -> false); throw new AssertionError("UTF-8 silently replaced a surrogate"); }
        catch (java.nio.charset.CharacterCodingException expected) { require(Objects.requireNonNull(exports.list()).length == 0, "Failed text staging leaked bytes"); }
        System.out.println("DEX provider navigation, snapshot provenance and strict export fixtures passed");
    }
}
