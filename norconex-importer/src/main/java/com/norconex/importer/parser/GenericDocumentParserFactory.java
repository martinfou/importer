/* Copyright 2010-2014 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.importer.parser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.ConfigurationException;
import com.norconex.commons.lang.config.ConfigurationUtil;
import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.importer.parser.impl.FallbackParser;
import com.norconex.importer.parser.impl.HTMLParser;
import com.norconex.importer.parser.impl.PDFParser;
import com.norconex.importer.parser.impl.wordperfect.WordPerfectParser;
import com.norconex.importer.response.ImporterResponse;

/**
 * Generic document parser factory.  It uses Apacke Tika for <i>most</i> of its 
 * supported content types.  For unknown
 * content types, it falls back to Tika generic media detector/parser.
 * <p />
 * <h3>Ignoring content types:</h3>
 * As of version 2.0.0, you can "ignore" content-types so they do not get
 * parsed.  Unparsed documents will be sent as is to the post handlers 
 * and the calling application.   Use caution when using that feature since
 * post-parsing handlers (or applications) usually expect text-only content for 
 * them to execute properly.  Unless you really know what you are doing, <b> 
 * avoid excluding binary content types from parsing.</b>
 * <p />
 * <h3>Embedded documents:</h3>
 * For documents containing embedded documents (e.g. zip files), the default 
 * behavior of this treat them as a single document, merging all
 * embedded documents content and metadata into the parent document.
 * As of version 2.0.0, you can tell this parser to "split" embedded
 * documents to have them treated as if they were individual documents.  When
 * split, each embedded documents will go through the entire import cycle, 
 * going through your handlers and even this parser again
 * (just like any regular document would).  The resulting 
 * {@link ImporterResponse} should then contain nested documents, which in turn,
 * might contain some (tree-like structure). 
 * <p />
 * <h3>XML configuration usage:</h3>
 * (Not required since used by default)
 * <p />
 * <pre>
 *  &lt;documentParserFactory 
 *          class="com.norconex.importer.parser.GenericDocumentParserFactory" 
 *          splitEmbedded="(false|true)" &gt;
 *      &lt;ignoredContentTypes&gt;
 *          (optional regex matching content types to ignore for parsing, 
 *           i.e., not parsed.)
 *      &lt;/ignoredContentTypes&gt;
 *  &lt;/documentParserFactory&gt;
 * </pre>
 * @author Pascal Essiembre
 */
@SuppressWarnings("nls")
public class GenericDocumentParserFactory 
        implements IDocumentParserFactory, IXMLConfigurable {

    private final Map<ContentType, IDocumentParser> namedParsers = 
            new HashMap<ContentType, IDocumentParser>();
    private IDocumentParser fallbackParser;

    private String ignoredContentTypesRegex;
    private boolean splitEmbedded;
    private boolean parsersAllSet = false;
    
    /**
     * Creates a new document parser factory of the given format.
     */
    public GenericDocumentParserFactory() {
        super();
        registerNamedParsers();
        registerFallbackParser();
    }

    /**
     * Gets a parser based on content type, regardless of document reference
     * (ignoring it).
     */
    @Override
    public final IDocumentParser getParser(
            String documentReference, ContentType contentType) {
        ensureParsersAllSet();
        // If ignoring content-type, do not even return a parser
        if (contentType != null 
                && StringUtils.isNotBlank(ignoredContentTypesRegex)
                && contentType.toString().matches(ignoredContentTypesRegex)) {
            return null;
        }
        
        IDocumentParser parser = namedParsers.get(contentType);
        if (parser == null) {
            return fallbackParser;
        }
        return parser;
    }
    
    public String getIgnoredContentTypesRegex() {
        return ignoredContentTypesRegex;
    }
    public void setIgnoredContentTypesRegex(String ignoredContentTypesRegex) {
        this.ignoredContentTypesRegex = ignoredContentTypesRegex;
    }

    public boolean isSplitEmbedded() {
        return splitEmbedded;
    }
    public void setSplitEmbedded(boolean splitEmbedded) {
        this.splitEmbedded = splitEmbedded;
    }

    protected final void registerNamedParser(
            ContentType contentType, IDocumentParser parser) {
        parsersAllSet = false;
        namedParsers.put(contentType, parser);
    }
    protected final void registerFallbackParser(IDocumentParser parser) {
        parsersAllSet = false;
        this.fallbackParser = parser;
    }
    protected final IDocumentParser getFallbackParser() {
        ensureParsersAllSet();
        return fallbackParser;
    }

    private void registerNamedParsers() {
        registerNamedParser(ContentType.HTML, new HTMLParser());

        IDocumentParser pdfParser = new PDFParser();
        registerNamedParser(ContentType.PDF, pdfParser);
        registerNamedParser(
                ContentType.valueOf("application/x-pdf"), pdfParser);

        IDocumentParser wpParser = new WordPerfectParser();
        registerNamedParser(
                ContentType.valueOf("application/wordperfecet"), wpParser);
        registerNamedParser(
                ContentType.valueOf("application/wordperfect6.0"), wpParser);
        registerNamedParser(
                ContentType.valueOf("application/wordperfect6.1"), wpParser);
    }
    private void registerFallbackParser() {
        registerFallbackParser(new FallbackParser());
    }
    
    private void ensureParsersAllSet() {
        if (!parsersAllSet) {
            for (IDocumentParser parser : namedParsers.values()) {
                if (parser instanceof IDocumentSplittableEmbeddedParser) {
                    ((IDocumentSplittableEmbeddedParser) parser)
                            .setSplitEmbedded(splitEmbedded);
                }
            }
            if (fallbackParser instanceof IDocumentSplittableEmbeddedParser) {
                ((IDocumentSplittableEmbeddedParser) fallbackParser)
                        .setSplitEmbedded(splitEmbedded);
            }
            parsersAllSet = true;
        }
    }
    
    
    @Override
    public void loadFromXML(Reader in) throws IOException {
        try {
            XMLConfiguration xml = ConfigurationUtil.newXMLConfiguration(in);
            setIgnoredContentTypesRegex(xml.getString(
                    "ignoredContentTypes", getIgnoredContentTypesRegex()));
            setSplitEmbedded(xml.getBoolean(
                    "[@splitEmbedded]", isSplitEmbedded()));
        } catch (ConfigurationException e) {
            throw new IOException("Cannot load XML.", e);
        }
    }

    @Override
    public void saveToXML(Writer out) throws IOException {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out);
            writer.writeStartElement("tagger");
            writer.writeAttribute("class", getClass().getCanonicalName());
            writer.writeAttribute("splitEmbedded", 
                    Boolean.toString(isSplitEmbedded()));
            if (ignoredContentTypesRegex != null) {
                writer.writeStartElement("ignoredContentTypes");
                writer.writeCharacters(ignoredContentTypesRegex);
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }
    }

}
