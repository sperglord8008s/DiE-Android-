// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.IOException;
import com.google.re2j.Pattern;

/** Independent Python-re-compatible search subset on source-built RE2/J; never backtracks. */
final class TrackerRegex {
    private static final String WORD=TrackerUnicode.WORD,SPACE=TrackerUnicode.SPACE,DIGIT=TrackerUnicode.DIGIT;
    static Pattern compile(String source) throws IOException {
        if(source.length()>1024) throw new IOException("Pattern exceeds 1,024 UTF-16 units");
        StringBuilder converted=new StringBuilder();boolean bracket=false;
        for(int i=0;i<source.length();i++) {
            char unit=source.charAt(i);
            if(Character.isSurrogate(unit)||unit=='\n'||unit=='\r') throw new IOException("Non-BMP/surrogate or line-break pattern literal is outside the compared subset");
            if(unit=='\\') {
                if(++i==source.length()) throw new IOException("Trailing regex escape");char escape=source.charAt(i);
                switch(escape) {
                    case 'd': converted.append(bracket?DIGIT:"["+DIGIT+"]");break;
                    case 'D':
                        if(bracket) throw new IOException("Complement digit shorthand inside a class is unsupported");
                        converted.append("[^").append(DIGIT).append(']');break;
                    case 'w': converted.append(bracket?WORD:"["+WORD+"]");break;
                    case 's': converted.append(bracket?SPACE:"["+SPACE+"]");break;
                    case 'W': case 'S':
                        if(bracket) throw new IOException("Complement shorthand inside a class is unsupported");
                        converted.append("[^").append(escape=='W'?WORD:SPACE).append(']');break;
                    case 'A': if(bracket) throw new IOException("Anchor inside a class");converted.append("\\A");break;
                    case 'Z': if(bracket) throw new IOException("Anchor inside a class");converted.append("\\z");break;
                    case 'b': if(!bracket) throw new IOException("Python Unicode word boundary is unsupported");converted.append("\\x{8}");break;
                    case 't': converted.append("\\t");break;
                    case 'n': converted.append("\\n");break;
                    case 'r': converted.append("\\r");break;
                    case 'f': converted.append("\\f");break;
                    case 'v': converted.append("\\x{B}");break;
                    case 'a': converted.append("\\x{7}");break;
                    default:
                        if("^$\\.*+?()[]{}|/-".indexOf(escape)<0) throw new IOException("Escape/backreference is outside the compared Python subset: \\"+escape);
                        converted.append('\\').append(escape);
                }
            } else {
                if(unit=='[') { if(bracket) throw new IOException("Nested/POSIX character class is unsupported");bracket=true; }
                else if(unit==']') { if(!bracket) throw new IOException("Unmatched/literal closing class bracket");bracket=false; }
                else if(bracket&&unit=='&'&&i+1<source.length()&&source.charAt(i+1)=='&') throw new IOException("Class intersection is unsupported");
                else if(!bracket&&unit=='('&&i+1<source.length()&&source.charAt(i+1)=='?'&&(i+2>=source.length()||source.charAt(i+2)!=':')) throw new IOException("Flags/lookaround/named groups are outside the compared subset");
                else if(!bracket&&"*+?}".indexOf(unit)>=0&&i+1<source.length()&&source.charAt(i+1)=='+') throw new IOException("Possessive repetition is unsupported");
                else if(!bracket&&unit=='{') {
                    int end=i+1;
                    while(end<source.length()&&asciiDigit(source.charAt(end))) end++;
                    if(end==i+1) throw new IOException("Literal opening brace is unsupported");
                    if(end<source.length()&&source.charAt(end)==',') { end++;while(end<source.length()&&asciiDigit(source.charAt(end))) end++; }
                    if(end>=source.length()||source.charAt(end)!='}') throw new IOException("Invalid repetition");
                    converted.append(source,i,end+1);i=end;continue;
                } else if(!bracket&&unit=='}') throw new IOException("Literal closing brace is unsupported");
                converted.append(unit);
            }
        }
        if(bracket) throw new IOException("Unterminated character class");
        try { return Pattern.compile(converted.toString()); }
        catch(IllegalArgumentException invalid) { throw new IOException("Unsupported/invalid regular expression: "+invalid.getMessage(),invalid); }
    }
    private static boolean asciiDigit(char value) { return value>='0'&&value<='9'; }
    /** Accepted names contain no LF (Python $ has a special final-LF case), or isolated surrogates. */
    static boolean inputSupported(String value) {
        if(value.length()>16384||value.indexOf('\n')>=0) return false;
        for(int i=0;i<value.length();i++) if(Character.isSurrogate(value.charAt(i))) {
            if(!Character.isHighSurrogate(value.charAt(i))||i+1==value.length()||!Character.isLowSurrogate(value.charAt(++i))) return false;
        }
        return true;
    }
}
