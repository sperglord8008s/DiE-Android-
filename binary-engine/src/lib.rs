// SPDX-License-Identifier: GPL-3.0-only
use aho_corasick::AhoCorasick;
use binwalk::Binwalk;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::os::fd::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};

const LIMIT: usize = 64 * 1024 * 1024;
const MAX_CANDIDATES: usize = 20000;
const MAX_OUTPUT: usize = 32 * 1024 * 1024;
const SOURCE: &str = "26713972e3f9f52dc37d4a421c4554b0bd9e82ef";

fn scan(data: &[u8]) -> Result<Value, String> {
    if data.len() > LIMIT { return Err("Binwalk input exceeds 64 MiB".into()); }
    // Full search includes short patterns at every offset. Do not call scan(),
    // which prunes nested results and infers unknown extents to the next hit/EOF.
    let engine = Binwalk::configure(None, None, None, None, None, true)
        .map_err(|error| error.message)?;
    let search = AhoCorasick::new(&engine.patterns).map_err(|error| error.to_string())?;
    let mut rows = Vec::new();
    let mut seen = HashSet::new();
    let mut matches = 0;
    let mut accepted = 0;
    let mut rejected = 0;
    let mut panics = 0;
    for hit in search.find_overlapping_iter(data) {
        matches += 1;
        if matches > MAX_CANDIDATES { return Err("More than 20,000 raw magic matches; no complete result returned".into()); }
        let pattern = hit.pattern().as_usize();
        let signature = &engine.pattern_signature_table[&pattern];
        let result = catch_unwind(AssertUnwindSafe(|| (signature.parser)(data, hit.start())));
        let row = match result {
            Ok(Ok(result)) => {
                let fits = result.offset <= data.len() && result.size <= data.len() - result.offset;
                if !fits { return Err(format!("{} parser returned an out-of-input extent",signature.name)); }
                accepted += 1;
                json!({"name":signature.name,"description":result.description,"magicOffset":hit.start(),"patternIndex":pattern,
                    "physicalOffset":result.offset,"reportedSizeBytes":if result.size==0{Value::Null}else{json!(result.size)},
                    "extentBasis":if result.size==0{"unknown; no EOF/next-hit inference"}else{"upstream parser reported, bounded to input"},
                    "parserAccepted":true,"parserStatus":"accepted","confidenceRaw":result.confidence,"extractionDeclined":result.extraction_declined,
                    "shortPattern":signature.short,"alwaysDisplay":signature.always_display})
            }
            Ok(Err(_)) => {
                rejected += 1;
                json!({"name":signature.name,"description":signature.description,"magicOffset":hit.start(),"patternIndex":pattern,
                    "physicalOffset":hit.start(),"reportedSizeBytes":Value::Null,"extentBasis":"unknown",
                    "parserAccepted":false,"parserStatus":"rejected","confidenceRaw":Value::Null,"reason":"upstream SignatureError; no detailed reason supplied", "shortPattern":signature.short})
            }
            Err(_) => {
                panics += 1;
                json!({"name":signature.name,"description":signature.description,"magicOffset":hit.start(),"patternIndex":pattern,"physicalOffset":hit.start(),
                    "reportedSizeBytes":Value::Null,"extentBasis":"unknown","parserAccepted":false,"parserStatus":"panicked","confidenceRaw":Value::Null,"reason":"upstream parser panicked; this candidate remains unresolved"})
            }
        };
        // One candidate per signature, raw magic position and parser result.
        let key = (signature.name.clone(),hit.start(),row["parserAccepted"].as_bool().unwrap());
        if seen.insert(key) { rows.push(row); }
    }
    rows.sort_by_key(|row| (row["physicalOffset"].as_u64().unwrap_or(0),row["patternIndex"].as_u64().unwrap_or(0)));
    let patterns: Vec<Value> = engine.patterns.iter().enumerate().map(|(index, bytes)| {
        let signature = &engine.pattern_signature_table[&index];
        json!({"index":index,"name":signature.name,"description":signature.description,"shortPattern":signature.short,
            "magicOffsetForShortMode":signature.magic_offset,"magicHex":bytes.iter().map(|b|format!("{b:02x}")).collect::<String>()})
    }).collect();
    Ok(json!({"schema":"org.diemobile.binwalk-evidence","schemaVersion":1,"ok":true,"engine":"Binwalk","engineVersion":"3.1.1","sourceRevision":SOURCE,
        "fileSizeBytes":data.len(),"signatureCount":engine.signature_count,"patternCount":engine.pattern_count,"rawMagicMatches":matches,
        "acceptedParserCalls":accepted,"rejectedParserCalls":rejected,"parserPanics":panics,"parserCoverageComplete":panics==0,"candidateCount":rows.len(),
        "rows":rows,"patterns":patterns,"fullBlobSearch":true,"nestedResultsPruned":false,"maxInputBytes":LIMIT,"maxRawMagicMatches":MAX_CANDIDATES,
        "unknownExtentsInferred":false,"externalExtractorsExecuted":false,"networkUsed":false}))
}
fn read(mut file: File) -> Result<Value,String> {
    let size = file.metadata().map_err(|e|e.to_string())?.len();
    if size > LIMIT as u64 { return Err("Binwalk input exceeds 64 MiB".into()); }
    file.seek(SeekFrom::Start(0)).map_err(|e|e.to_string())?;
    let mut bytes = Vec::with_capacity(size as usize);
    file.take((LIMIT+1) as u64).read_to_end(&mut bytes).map_err(|e|e.to_string())?;
    if bytes.len()!=size as usize { return Err("Input length changed during Binwalk acquisition".into()); }
    scan(&bytes)
}
fn respond(mut env: JNIEnv, work: impl FnOnce(&mut JNIEnv)->Result<Value,String>) -> jstring {
    let value = match catch_unwind(AssertUnwindSafe(|| work(&mut env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"ok":false,"error":error}),
        Err(_) => json!({"ok":false,"error":"Binwalk parser panicked; no complete result returned"})
    };
    let encoded = value.to_string();
    let output = if encoded.len() > MAX_OUTPUT {"{\"ok\":false,\"error\":\"Binwalk report exceeds 32 MiB\"}"}else{&encoded};
    match env.new_string(output) { Ok(value)=>value.into_raw(), Err(_)=>std::ptr::null_mut() }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_diemobile_BinwalkEngine_scanFd(env: JNIEnv,_class:JClass,fd:jint)->jstring {
    respond(env,|_| {
        let copy = unsafe {libc::dup(fd)};
        if copy<0 { return Err("Unable to duplicate granted input descriptor".into()); }
        read(unsafe {File::from_raw_fd(copy)})
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_diemobile_BinwalkEngine_scanPath(env: JNIEnv,_class:JClass,path:JString)->jstring {
    respond(env,|env| { let path:String=env.get_string(&path).map_err(|e|e.to_string())?.into(); read(File::open(path).map_err(|e|e.to_string())?) })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn empty_is_complete_without_unknown_range_inference() {
        let result=scan(&[]).unwrap(); assert!(result["ok"].as_bool().unwrap());assert!(result["rows"].as_array().unwrap().is_empty());
    }
}
