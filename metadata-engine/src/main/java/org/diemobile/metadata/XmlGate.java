// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.metadata;

import java.io.*;
import java.util.function.BooleanSupplier;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded SAX preflight before upstream XMP DOM parsing; no DTD/URI resolution. */
public final class XmlGate {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_CHARACTERS = 4 * 1024 * 1024, MAX_DEPTH = 64, MAX_NODES = 100000;
    private static final ThreadLocal<BooleanSupplier> CANCELLED = new ThreadLocal<>();
    private XmlGate() {}
    public static AutoCloseable cancellation(BooleanSupplier cancelled) {
        BooleanSupplier previous = CANCELLED.get(); CANCELLED.set(cancelled);
        return () -> { if (previous == null) CANCELLED.remove(); else CANCELLED.set(previous); };
    }
    private static void check() throws InterruptedIOException {
        BooleanSupplier cancelled = CANCELLED.get(); if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedIOException("Metadata XML cancelled");
    }
    public static InputSource validate(InputSource source) throws IOException, SAXException, ParserConfigurationException {
        check(); final byte[] bytes; final String characters;
        if (source.getCharacterStream() != null) {
            StringBuilder result = new StringBuilder(); char[] buffer = new char[4096]; int count;
            while ((count = source.getCharacterStream().read(buffer)) >= 0) { check(); if (count > MAX_CHARACTERS-result.length()) throw new IOException("XMP exceeds four Mi UTF-16 units"); result.append(buffer,0,count); }
            characters = result.toString(); bytes = null;
        } else if (source.getByteStream() != null) {
            ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = source.getByteStream().read(buffer)) >= 0) { check(); if (count > MAX_BYTES-result.size()) throw new IOException("XMP exceeds eight MiB"); result.write(buffer,0,count); }
            bytes = result.toByteArray(); characters = null;
        } else throw new SAXException("XML must supply bytes or characters; URI-only input is prohibited");
        SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true); factory.setValidating(false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        DefaultHandler2 handler = new DefaultHandler2() {
            private int depth, nodes, text;
            @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { throw new SAXException("DTD declarations are prohibited in metadata XML"); }
            @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException { throw new SAXException("External XML entities are prohibited"); }
            @Override public InputSource resolveEntity(String name, String publicId, String base, String systemId) throws SAXException { throw new SAXException("External XML entities are prohibited"); }
            @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
                checked(); if (++depth > MAX_DEPTH || ++nodes > MAX_NODES || attributes.getLength() > 10000) throw new SAXException("Metadata XML depth/node/attribute budget exceeded");
                for (int i = 0; i < attributes.getLength(); i++) { text += attributes.getValue(i).length(); if (text > MAX_CHARACTERS) throw new SAXException("Metadata XML value budget exceeded"); }
            }
            @Override public void endElement(String uri, String local, String name) { depth--; }
            @Override public void characters(char[] data, int offset, int count) throws SAXException { checked(); text += count; if (text > MAX_CHARACTERS) throw new SAXException("Metadata XML text budget exceeded"); }
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
            private void checked() throws SAXException { try { check(); } catch (IOException error) { throw new SAXException(error); } }
        };
        // Fail if lexical DTD callbacks cannot be installed. Never silently fall
        // back to a parser that might resolve external data or expand a DTD.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);
        reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
        reader.parse(input(bytes,characters,source.getEncoding())); check();
        return input(bytes,characters,source.getEncoding());
    }
    private static InputSource input(byte[] bytes, String characters, String encoding) {
        InputSource result = characters == null ? new InputSource(new ByteArrayInputStream(bytes)) : new InputSource(new StringReader(characters));
        result.setEncoding(encoding); return result;
    }
}
