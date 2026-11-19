// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Required-literal Aho-Corasick prefilter. RE2/J remains the match/span authority. */
final class TrackerPrefilter {
    static final int MAX_STATES=65536;
    private static final class State {
        final Map<Character,Integer> edges=new HashMap<>();
        final List<Integer> rules=new ArrayList<>();
        int failure,output;
    }
    static final class Scan {
        final BitSet candidates=new BitSet();
        long units,steps;
        boolean complete=true;
    }
    private final List<State> states=new ArrayList<>();
    final BitSet indexed=new BitSet();
    final int ruleCount;
    TrackerPrefilter(List<TrackerCatalog.Rule> rules,BooleanSupplier stop) throws Exception {
        states.add(new State());
        for(TrackerCatalog.Rule rule:rules) {
            EvidenceRows.check(stop);if(rule.pattern==null) continue;
            List<String> atoms=atoms(rule.signature);if(atoms==null) continue;
            int maximum=0;for(String atom:atoms) maximum+=atom.length();
            // A conservative capacity check leaves this rule on the ordinary
            // regex path. It must never disable a rule or partly index branches.
            if(maximum>MAX_STATES-states.size()) continue;
            for(String atom:atoms) {
                int state=0;
                for(int i=0;i<atom.length();i++) {
                    char unit=atom.charAt(i);Integer next=states.get(state).edges.get(unit);
                    if(next==null) { next=states.size();states.get(state).edges.put(unit,next);states.add(new State()); }
                    state=next;
                }
                states.get(state).rules.add(rule.index);
            }
            indexed.set(rule.index);
        }
        ArrayDeque<Integer> queue=new ArrayDeque<>(states.get(0).edges.values());
        while(!queue.isEmpty()) {
            EvidenceRows.check(stop);int parent=queue.removeFirst();State current=states.get(parent);
            for(Map.Entry<Character,Integer> edge:current.edges.entrySet()) {
                char unit=edge.getKey();int child=edge.getValue(),failure=current.failure;
                while(failure!=0&&!states.get(failure).edges.containsKey(unit)) failure=states.get(failure).failure;
                Integer target=states.get(failure).edges.get(unit);State next=states.get(child);
                next.failure=target==null?0:target;
                next.output=states.get(next.failure).rules.isEmpty()?states.get(next.failure).output:next.failure;
                queue.addLast(child);
            }
        }
        ruleCount=indexed.cardinality();
    }
    int stateCount() { return states.size(); }
    /** null means no proof was derived: use ordinary regex, without an omission. */
    static List<String> atoms(String pattern) {
        Set<String> required=new LinkedHashSet<>();StringBuilder literal=new StringBuilder();String longest="";
        for(int i=0;i<=pattern.length();i++) {
            char unit=i==pattern.length()?'|':pattern.charAt(i);
            if(unit=='\\') {
                if(++i>=pattern.length()) return null;char escaped=pattern.charAt(i);
                if("^$\\.*+?()[]{}|/-".indexOf(escaped)<0) return null;
                literal.append(escaped);
            } else if(unit=='.'||unit=='|') {
                if(literal.length()>longest.length()) longest=literal.toString();literal.setLength(0);
                if(unit=='|') {
                    if(longest.length()<3) return null;
                    required.add(longest);longest="";
                }
            } else if("^$*+?()[]{}".indexOf(unit)>=0||Character.isSurrogate(unit)) return null;
            else literal.append(unit);
        }
        return new ArrayList<>(required);
    }
    /** Complete means every indexed rule has either a candidate or a proven required-atom miss. */
    Scan scan(String input,BooleanSupplier stop,long started,long nanos,long maxUnits,long maxSteps) throws Exception {
        Scan result=new Scan();int state=0,candidateCount=0;long nextCheck=0;
        for(int i=0;i<input.length();i++) {
            if(result.units>=maxUnits||result.steps>=maxSteps) { result.complete=false;return result; }
            if(result.steps>=nextCheck) { EvidenceRows.check(stop);nextCheck=result.steps+1024;if(System.nanoTime()-started>nanos) { result.complete=false;return result; } }
            char unit=input.charAt(i);result.units++;result.steps++;
            Integer edge=states.get(state).edges.get(unit);
            while(state!=0&&edge==null) {
                if(++result.steps>maxSteps) { result.complete=false;return result; }
                state=states.get(state).failure;edge=states.get(state).edges.get(unit);
            }
            state=edge==null?0:edge;
            for(int terminal=state;terminal!=0;terminal=states.get(terminal).output) {
                for(int rule:states.get(terminal).rules) {
                    if(++result.steps>maxSteps) { result.complete=false;return result; }
                    if(result.steps>=nextCheck) { EvidenceRows.check(stop);nextCheck=result.steps+1024;if(System.nanoTime()-started>nanos) { result.complete=false;return result; } }
                    if(!result.candidates.get(rule)) { result.candidates.set(rule);candidateCount++; }
                }
            }
            if(candidateCount==ruleCount) return result;
        }
        EvidenceRows.check(stop);return result;
    }
}
