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
package com.norconex.importer.parser.impl.wordperfect;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.io.IOUtils;

import com.norconex.importer.doc.ImporterDocument;
import com.norconex.importer.parser.DocumentParserException;
import com.norconex.importer.parser.IDocumentParser;


/**
 * Parser for WordPerfect documents.  Only text from the body is extracted,
 * and no metadata is added.
 * <p/>
 * Implementation is derived from 
 * <a href="http://sourceforge.net/projects/logicaldoc/">LogicalDoc</a> (LPGL3)
 * WordPerfect parsing code.
 * @author Pascal Essiembre 
 * @since 1.2.0
 */
//NOTE: Excluded from Sonar coverage calculations.
public class WordPerfectParser implements IDocumentParser {

    private static final String END_OF_LINE = 
            System.getProperty("line.separator", "\n");
    
    private static final String[] EXACT_START_LINES = {
        "doc init", "tech init"
    };

    private static final String[] START_EXCLUDES = {
        "wpc", "monotype sorts", "section", "columns", "aligned ", 
        "standard", "default ", "biblio", "footnote", "gfootnote", 
        "endnote", "heading", "header for ", "underlined heading", 
        "centered heading", "technical", "object #",
        "microsoft word"
    };

    private static final String[] END_EXCLUDES = {
        "aligned paragraph numbers", "heading", "bullet list"
        // " style", " roman", "laserJet", "bullet list", "defaults", 
        // "typestyle", "land", "landscape", "portrait"
    };

    private static final String[] EXACT_EXCLUDES = {
        "nlus.", "usjp", "initialize technical style", "document style", 
        "pleading", "times", "and", "where", "left", "right", "over", 
        "(k over", "document", "header", "footer", "itemize", "page number", 
        "pages", "body text", "word", "sjablone", "d printer"
    };

    private static final String[] CONTAIN_EXCLUDES = {
        "left (", "right )", "right ]", "right par", "default paragraph"
    };

    
    @Override
    public List<ImporterDocument> parseDocument(ImporterDocument doc,
            Writer output) throws DocumentParserException {
        try {
            output.write(extract(doc.getContent()).trim());
        } catch (IOException e) {
            throw new DocumentParserException(e);
        }
        return null;
    }
    
//    @Override
//    public List<ImporterDocument> parseDocument(
//            String reference,
//            InputStream inputStream, ContentType contentType,
//            Writer outputStream, ImporterMetadata metadata)
//            throws DocumentParserException {
//        try {
//            outputStream.write(extract(inputStream).trim());
//        } catch (IOException e) {
//            throw new DocumentParserException(e);
//        }
//        return null;
//    }
    
    /**
     * Wraps the specified InputStream in a WPFilterInputStream.
     * @param stream a WordPerfect stream
     * @return extracted text
     * @throws IOException problem extracting text
     */
    private String extract(InputStream stream) throws IOException {
        WordPerfectInputStream wpfis = new WordPerfectInputStream(stream);
        StringBuilder lineBuffer = new StringBuilder(512);
        StringBuilder textBuffer = new StringBuilder(64 * 1024);

        int b = -1;
        while ((b = wpfis.read()) != -1) {
            // test whether it is a reasonable readable character
            if (isTextCharacter(b)) {
                // append to the current line we're processing
                lineBuffer.append((char) b);
            } else if (lineBuffer.length() > 0) {
                // it's not and therefore marks an end of line
                // process the line we have gathered
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);

                // perform some post processing, possibly invalidating the 
                // found line of text
                line = postProcessLine(line);

                if (line != null) {
                    String lineLowerCase = line.toLowerCase();

                    if (isStartLine(lineLowerCase)) {
                        // scrap everything until this start line and 
                        // continue with the next
                        textBuffer.setLength(0);
                    } else if (isValidLine(lineLowerCase)) {
                        // append the original, non-lowercased line to the 
                        // end result and continue processing the stream
                        textBuffer.append(line);
                        textBuffer.append(END_OF_LINE);
                    }
                }
            }
        }
        IOUtils.closeQuietly(wpfis);
        return textBuffer.toString();
    }
    
    private String postProcessLine(String line) {
        line = line.trim();

        if (line.length() <= 2) {
            // line too short
            line = null;
        } else {
            // line should contain at least one 'normal' word
            boolean containsWord = false;

            StringTokenizer st = new StringTokenizer(line, " ");
            while (st.hasMoreTokens() && containsWord == false) {
                containsWord = isNormalWord(st.nextToken());
            }

            if (!containsWord) {
                line = null;
            }
        }

        return line;
    }
    
    // overrides StringExtractor.isTextCharacter
   private boolean isTextCharacter(int charNumber) {
               // readable ASCII characters
        return charNumber >= 32 && charNumber <= 126 
                // letters with accents, currency symbols, etc.
                || charNumber >= 128 && charNumber <= 168
                // tab
                || charNumber == 9 
                // accented ANSI character
                || charNumber >= 0xC0 && charNumber <= 0xFF
                // backquote
                || charNumber == 0x91 
                // quote
                || charNumber == 0x92;
    }

    // overrides StringExtractor.isStartLine
    private boolean isStartLine(String lineLowerCase) {
        for (int i = 0; i < EXACT_START_LINES.length; i++) {
            if (lineLowerCase.equals(EXACT_START_LINES[i])) {
                return true;
            }
        }
        return false;
    }

    // overrides StringExtractor.isValidLine
    private boolean isValidLine(String lineLowerCase) {
        for (int i = 0; i < EXACT_EXCLUDES.length; i++) {
            if (lineLowerCase.equals(EXACT_EXCLUDES[i])) {
                return false;
            }
        }

        for (int i = 0; i < START_EXCLUDES.length; i++) {
            if (lineLowerCase.startsWith(START_EXCLUDES[i])) {
                return false;
            }
        }

        for (int i = 0; i < END_EXCLUDES.length; i++) {
            if (lineLowerCase.endsWith(END_EXCLUDES[i])) {
                return false;
            }
        }

        // most expensive operation: make sure this is the last check
        for (int i = 0; i < CONTAIN_EXCLUDES.length; i++) {
            if (lineLowerCase.indexOf(CONTAIN_EXCLUDES[i]) >= 0) {
                return false;
            }
        }
        return true;
    }
    
    private boolean isNormalWord(String word) {
        boolean result = false;

        int wordLength = word.length();

        if (wordLength > 0) {
            char lastChar = word.charAt(wordLength - 1);
            if (lastChar == '.' || lastChar == ',') {
                wordLength--;
            }
        }

        if (wordLength >= 3) {
            result = true;

            // check if the word contains non-letter characters
            for (int i = 0; i < wordLength && result == true; i++) {
                if (!Character.isLetter(word.charAt(i))) {
                    result = false;
                }
            }

            // Check use of upper- and lower case. Note that we can't check for
            // lower case, as some non-Latin characters
            // do not have a case at all.
            // Allowed patterns are:
            // - all upper case
            // - none upper case
            // - first character upper case, rest not upper case
            if (Character.isUpperCase(word.charAt(0))) {
                if (Character.isUpperCase(word.charAt(1))) {
                    // all upper case?
                    for (int i = 2; i < wordLength && result == true; i++) {
                        result = Character.isUpperCase(word.charAt(i));
                    }
                } else {
                    // rest not upper case?
                    for (int i = 2; i < wordLength && result == true; i++) {
                        result = !Character.isUpperCase(word.charAt(i));
                    }
                }
            } else {
                // all not upper case?
                for (int i = 0; i < wordLength && result == true; i++) {
                    result = !Character.isUpperCase(word.charAt(i));
                }
            }

            // check character frequency
            if (result == true) {
                Map<Character, Integer> charFreq = 
                        new HashMap<Character, Integer>(32);
                for (int i = 0; i < wordLength; i++) {
                    Character c = new Character(word.charAt(i));

                    Integer freq = charFreq.get(c);
                    if (freq == null) {
                        freq = new Integer(1);
                    } else {
                        freq = new Integer(freq.intValue() + 1);
                    }
                    charFreq.put(c, freq);
                }

                // no word should consist for 50% or more of a single character
                int freqThreshold = wordLength / 2;

                Iterator<Integer> valueIter = charFreq.values().iterator();
                while (valueIter.hasNext() && result == true) {
                    Integer freq = valueIter.next();
                    result = (freq.intValue() < freqThreshold);
                }
            }
        }
        return result;
    }


}
