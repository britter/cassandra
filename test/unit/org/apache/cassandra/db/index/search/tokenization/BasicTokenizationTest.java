package org.apache.cassandra.db.index.search.tokenization;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class BasicTokenizationTest
{
    @Test
    public void testTokenizationAscii() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/apache_license_header.txt");

        StandardTokenizerOptions options = new StandardTokenizerOptions.OptionsBuilder()
                .maxTokenLength(5).build();
        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(options);

        List<ByteBuffer> tokens = new ArrayList<>();
        tokenizer.reset(is);
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(67, tokens.size());
    }

    @Test
    public void testTokenizationLoremIpsum() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/lorem_ipsum.txt");

        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);

        List<ByteBuffer> tokens = new ArrayList<>();
        tokenizer.reset(is);
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(62, tokens.size());

    }

    @Test
    public void testTokenizationJaJp1() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/ja_jp_1.txt");

        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);

        tokenizer.reset(is);
        List<ByteBuffer> tokens = new ArrayList<>();
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(210, tokens.size());
    }

    @Test
    public void testTokenizationJaJp2() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/ja_jp_2.txt");

        StandardTokenizerOptions options = new StandardTokenizerOptions.OptionsBuilder().stemTerms(true)
                .ignoreStopTerms(true).alwaysLowerCaseTerms(true).build();
        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(options);

        tokenizer.reset(is);
        List<ByteBuffer> tokens = new ArrayList<>();
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(57, tokens.size());
    }

    @Test
    public void testTokenizationRuRu1() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/ru_ru_1.txt");
        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);

        List<ByteBuffer> tokens = new ArrayList<>();
        tokenizer.reset(is);
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(456, tokens.size());
    }

    @Test
    public void testTokenizationZnTw1() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/zn_tw_1.txt");
        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);

        List<ByteBuffer> tokens = new ArrayList<>();
        tokenizer.reset(is);
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(963, tokens.size());
    }

    @Test
    public void testTokenizationAdventuresOfHuckFinn() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/adventures_of_huckleberry_finn_mark_twain.txt");

        StandardTokenizerOptions options = new StandardTokenizerOptions.OptionsBuilder().stemTerms(true)
                .ignoreStopTerms(true).useLocale(Locale.ENGLISH)
                .alwaysLowerCaseTerms(true).build();
        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(options);

        List<ByteBuffer> tokens = new ArrayList<>();
        tokenizer.reset(is);
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(40249, tokens.size());
    }

    @Test
    public void tokenizeDomainNamesAndUrls() throws Exception
    {
        InputStream is = BasicTokenizationTest.class.getClassLoader()
                .getResourceAsStream("tokenization/top_visited_domains.txt");

        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);
        tokenizer.reset(is);

        List<ByteBuffer> tokens = new ArrayList<>();
        while (tokenizer.hasNext())
            tokens.add(tokenizer.next());

        assertEquals(15, tokens.size());
    }

    @Test
    public void testReuseAndResetTokenizerInstance() throws Exception
    {
        List<ByteBuffer> bbToTokenize = new ArrayList<>();
        bbToTokenize.add(ByteBuffer.wrap("Nip it in the bud".getBytes()));
        bbToTokenize.add(ByteBuffer.wrap("I couldn’t care less".getBytes()));
        bbToTokenize.add(ByteBuffer.wrap("One and the same".getBytes()));
        bbToTokenize.add(ByteBuffer.wrap("The squeaky wheel gets the grease.".getBytes()));
        bbToTokenize.add(ByteBuffer.wrap("The pen is mightier than the sword.".getBytes()));

        StandardTokenizer tokenizer = new StandardTokenizer();
        tokenizer.init(StandardTokenizerOptions.DEFAULT);

        List<ByteBuffer> tokens = new ArrayList<>();
        for (ByteBuffer bb : bbToTokenize)
        {
            tokenizer.reset(bb);
            while (tokenizer.hasNext())
                tokens.add(tokenizer.next());
        }
        assertEquals(10, tokens.size());
    }
}
