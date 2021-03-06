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
package com.norconex.importer.parser.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaMetadataKeys;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.io.CachedOutputStream;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.importer.doc.ImporterDocument;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.parser.DocumentParserException;
import com.norconex.importer.parser.IDocumentSplittableEmbeddedParser;

/**
 * Base class wrapping Apache Tika parser for use by the importer.
 * @author Pascal Essiembre
 */
public class AbstractTikaParser implements IDocumentSplittableEmbeddedParser {

    private final Parser parser;
    private boolean splitEmbedded;

    /**
     * Creates a new Tika-based parser.
     * @param parser Tika parser
     */
    public AbstractTikaParser(Parser parser) {
        super();
        this.parser = parser;
    }
    
    //TODO distinguish between archives and other embedded docs and offer
    //     a flag for each whether to split them (as opposed to the generic
    //     splitEmbedded variable). Detected based whether there is a name for 
    //     the item.
    //TODO have a maximum recursivity setting somewhere???
    //TODO have a flag that says whether to process archive/package files only
    //     or also embedded objects in documents (e.g. image in MS-word).
    

    @Override
    public final List<ImporterDocument> parseDocument(
            ImporterDocument doc, Writer output)
            throws DocumentParserException {

        Metadata tikaMetadata = new Metadata();
        tikaMetadata.set(HttpHeaders.CONTENT_TYPE, 
                doc.getContentType().toString());
        tikaMetadata.set(TikaMetadataKeys.RESOURCE_NAME_KEY, 
                doc.getReference());
        tikaMetadata.set(Metadata.CONTENT_ENCODING, doc.getContentEncoding());
        
        try {
            RecursiveParser recursiveParser = createRecursiveParser(
                    doc.getReference(), output, doc.getMetadata(), 
                    doc.getContent().getStreamFactory());
            
            ParseContext context = new ParseContext();
            context.set(Parser.class, recursiveParser);
            ContentHandler handler = new BodyContentHandler(output);
            recursiveParser.parse(doc.getContent(), 
                    handler, tikaMetadata, context);
            return recursiveParser.getEmbeddedDocuments();
        } catch (Exception e) {
            throw new DocumentParserException(e);
        }
    }

    public boolean isSplitEmbedded() {
        return splitEmbedded;
    }
    @Override
    public void setSplitEmbedded(boolean splitEmbedded) {
        this.splitEmbedded = splitEmbedded;
    }

    protected void addTikaMetadata(
            Metadata tikaMeta, ImporterMetadata metadata) {
        String[]  names = tikaMeta.names();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (TikaMetadataKeys.RESOURCE_NAME_KEY.equals(name)) {
                continue;
            }
            List<String> nxValues = metadata.getStrings(name);
            String[] tikaValues = tikaMeta.getValues(name);
            for (String tikaValue : tikaValues) {
                if (!nxValues.contains(tikaValue)) {
                    metadata.addString(name, tikaValue);
                }
            }
        }
    }

    protected RecursiveParser createRecursiveParser(
            String reference, Writer writer, 
            ImporterMetadata metadata, CachedStreamFactory streamFactory) {
        if (splitEmbedded) {
            return new SplitEmbbededParser(
                    reference, this.parser, metadata, streamFactory);
        } else {
            return new MergeEmbeddedParser(this.parser, writer, metadata);
        }
    }
    
    protected class SplitEmbbededParser 
            extends ParserDecorator implements RecursiveParser {
        private static final long serialVersionUID = -5011890258694908887L;
        private final String reference;
        private final ImporterMetadata metadata;
        private final CachedStreamFactory streamFactory;
        private boolean isMasterDoc = true;
        private int embedCount;
        private List<ImporterDocument> embeddedDocs;
        public SplitEmbbededParser(String reference, Parser parser, 
                ImporterMetadata metadata, CachedStreamFactory streamFactory) {
            super(parser);
            this.streamFactory = streamFactory;
            this.reference = reference;
            this.metadata = metadata;
        }
        @Override
        public void parse(InputStream stream, ContentHandler handler,
                Metadata tikaMeta, ParseContext context)
                throws IOException, SAXException, TikaException {
            
            if (isMasterDoc) {
                isMasterDoc = false;
                super.parse(stream, handler, tikaMeta, context);
                addTikaMetadata(tikaMeta, metadata);
            } else {
                embedCount++;
                if (embeddedDocs == null) {
                    embeddedDocs = new ArrayList<>();
                }

                ImporterMetadata embedMeta = new ImporterMetadata();

                String embedRef = reference + "!" + resolveEmbeddedResourceName(
                        tikaMeta, embedMeta, embedCount);

                // Read the steam into cache for reuse since Tika will
                // close the original stream on us causing exceptions later.
                CachedOutputStream embedOutput = streamFactory.newOuputStream();
                IOUtils.copy(stream, embedOutput);
                CachedInputStream embedInput = embedOutput.getInputStream();
                embedOutput.close();
                
                ImporterDocument embedDoc = new ImporterDocument(
                        embedRef, embedInput, embedMeta); 
                embedMeta.setReference(embedRef);
                embedMeta.setEmbeddedParentReference(reference);
                
                String rootRef = metadata.getEmbeddedParentRootReference();
                if (StringUtils.isBlank(rootRef)) {
                    rootRef = reference;
                }
                embedMeta.setEmbeddedParentRootReference(rootRef);
                
                embeddedDocs.add(embedDoc);
            }
        }
        
        public List<ImporterDocument> getEmbeddedDocuments() {
            return embeddedDocs;
        }
    }
    
    private String resolveEmbeddedResourceName(
            Metadata tikaMeta, ImporterMetadata embedMeta, int embedCount) {
        String name = null;
        
        // Package item file name (e.g. a file in a zip)
        name = tikaMeta.get(Metadata.EMBEDDED_RELATIONSHIP_ID);
        if (StringUtils.isNotBlank(name)) {
            embedMeta.setEmbeddedReference(name);
            embedMeta.setEmbeddedType("package-file");
            return name;
        }

        // Name of Embedded file in regular document 
        // (e.g. excel file in a word doc)
        name = tikaMeta.get(Metadata.RESOURCE_NAME_KEY);
        if (StringUtils.isNotBlank(name)) {
            embedMeta.setEmbeddedReference(name);
            embedMeta.setEmbeddedType("file-file");
            return name;
        }
        
        // Name of embedded content in regular document 
        // (e.g. image with no name in a word doc)
        // Make one up with content type (which should be OK most of the time).
        name = tikaMeta.get(Metadata.CONTENT_TYPE);
        if (StringUtils.isNotBlank(name)) {
            ContentType ct = ContentType.valueOf(name);
            if (ct != null) {
                embedMeta.setEmbeddedType("file-object");
                return "embedded-" + embedCount + "." + ct.getExtension();
            }
        }
        
        // Default... we could not find any name so make a unique one.
        embedMeta.setEmbeddedType("unknown");
        return "embedded-" + embedCount + ".unknown";
    }
    
    protected class MergeEmbeddedParser 
            extends ParserDecorator implements RecursiveParser  {
        private static final long serialVersionUID = -5011890258694908887L;
        private final Writer writer;
        private final ImporterMetadata metadata;
        public MergeEmbeddedParser(Parser parser, 
                Writer writer, ImporterMetadata metadata) {
            super(parser);
            this.writer = writer;
            this.metadata = metadata;
        }
        @Override
        public void parse(InputStream stream, ContentHandler handler,
                Metadata tikaMeta, ParseContext context)
                throws IOException, SAXException, TikaException {
            ContentHandler content = new BodyContentHandler(writer);
            super.parse(stream, content, tikaMeta, context);
            addTikaMetadata(tikaMeta, metadata);
        }
        @Override
        public List<ImporterDocument> getEmbeddedDocuments() {
            return null;
        }
    }
    
    protected interface RecursiveParser extends Parser {
        List<ImporterDocument> getEmbeddedDocuments();
    }
}
