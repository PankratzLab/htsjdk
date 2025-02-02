/*
 * The MIT License
 *
 * Copyright (c) 2013 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.tribble;

import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.seekablestream.SeekableStreamFactory;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.samtools.util.FileExtensions;
import htsjdk.tribble.index.Block;
import htsjdk.tribble.index.Index;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.readers.PositionalBufferedStream;
import htsjdk.tribble.util.ParsingUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * A reader for text feature files  (i.e. not tabix files).   This includes tribble-indexed and non-indexed files.  If
 * index both iterate() and query() methods are supported.
 * <p/>
 * Note: Non-indexed files can be gzipped, but not bgzipped.
 *
 * @author Jim Robinson
 * @since 2/11/12
 */
public class TribbleIndexedFeatureReader<T extends Feature, SOURCE> extends AbstractFeatureReader<T, SOURCE> {

    private Index index;

    /**
     * is the path pointing to our source data a regular file?
     */
    private final boolean pathIsRegularFile;

    /**
     * a potentially reusable seekable stream for queries over regular files
     */
    private SeekableStream seekableStream = null;

    /**
     * We lazy-load the index but it might not even exist
     * Don't want to keep checking if that's the case
     */
    private boolean needCheckForIndex = true;

    /**
     * @param featurePath  - path to the feature file, can be a local file path, http url, or ftp url
     * @param codec        - codec to decode the features
     * @param requireIndex - true if the reader will be queries for specific ranges.  An index (idx) file must exist
     * @throws IOException
     */
    public TribbleIndexedFeatureReader(final String featurePath, final FeatureCodec<T, SOURCE> codec, final boolean requireIndex) throws IOException {
        this(featurePath, codec, requireIndex, null, null);
    }

    public TribbleIndexedFeatureReader(final String featurePath, final FeatureCodec<T, SOURCE> codec, final boolean requireIndex,
                                       Function<SeekableByteChannel, SeekableByteChannel> wrapper,
                                       Function<SeekableByteChannel, SeekableByteChannel> indexWrapper) throws IOException {
        super(featurePath, codec, wrapper, indexWrapper);

        if (requireIndex) {
            this.loadIndex();
            if (!this.hasIndex()) {
                throw new TribbleException("An index is required, but none found with file ending " + FileExtensions.TRIBBLE_INDEX);
            }
        }

        // does path point to a regular file?
        this.pathIsRegularFile = SeekableStreamFactory.isFilePath(path);

        readHeader();
    }

    /**
     * @param featureFile  - path to the feature file, can be a local file path, http url, or ftp url
     * @param indexFile    - path to the index file
     * @param codec        - codec to decode the features
     * @param requireIndex - true if the reader will be queries for specific ranges.  An index (idx) file must exist
     * @throws IOException
     */
    public TribbleIndexedFeatureReader(final String featureFile, final String indexFile, final FeatureCodec<T, SOURCE> codec, final boolean requireIndex) throws IOException {
        this(featureFile, indexFile, codec, requireIndex, null, null);
    }

    /**
     * @param featureFile  - path to the feature file, can be a local file path, http url, or ftp url, or any other
     *                     uri supported by a {@link java.nio.file.Path} plugin
     * @param indexFile    - path to the index file
     * @param codec        - codec to decode the features
     * @param requireIndex - true if the reader will be queries for specific ranges.  An index (idx) file must exist
     * @throws IOException
     */
    public TribbleIndexedFeatureReader(final String featureFile, final String indexFile, final FeatureCodec<T, SOURCE> codec, final boolean requireIndex,
                                       Function<SeekableByteChannel, SeekableByteChannel> wrapper,
                                       Function<SeekableByteChannel, SeekableByteChannel> indexWrapper) throws IOException {
        this(featureFile, codec, false, wrapper, indexWrapper); // required to read the header
        if (indexFile != null && ParsingUtils.resourceExists(indexFile)) {
            index = IndexFactory.loadIndex(indexFile, indexWrapper);
            this.needCheckForIndex = false;
        } else {
            if (requireIndex) {
                this.loadIndex();
                if (!this.hasIndex()) {
                    throw new TribbleException("An index is required, but none found with file ending " + FileExtensions.TRIBBLE_INDEX);
                }
            }
        }
    }

    /**
     * @param featureFile - path to the feature file, can be a local file path, http url, or ftp url
     * @param codec       - codec to decode the features
     * @param index       - a tribble Index object
     * @throws IOException
     */
    public TribbleIndexedFeatureReader(final String featureFile, final FeatureCodec<T, SOURCE> codec, final Index index) throws IOException {
        this(featureFile, codec, false); // required to read the header
        this.index = index;
        this.needCheckForIndex = false;
    }

    /**
     * Attempt to load the index for the specified {@link #path}.
     * If the {@link #path} has no available index file,
     * does nothing
     *
     * @throws IOException
     */
    private void loadIndex() throws IOException {
        String indexFile = Tribble.indexFile(this.path);
        if (ParsingUtils.resourceExists(indexFile)) {
            index = IndexFactory.loadIndex(indexFile, indexWrapper);
        } else {
            // See if the index itself is gzipped
            indexFile = ParsingUtils.appendToPath(indexFile, ".gz");
            if (ParsingUtils.resourceExists(indexFile)) {
                index = IndexFactory.loadIndex(indexFile, indexWrapper);
            }
        }
        this.needCheckForIndex = false;
    }

    /**
     * Get a seekable stream appropriate to read information from the current feature path
     * <p/>
     * This function ensures that if reuseStreamInQuery returns true then this function will only
     * ever return a single unique instance of SeekableStream for all calls given this instance of
     * TribbleIndexedFeatureReader.  If reuseStreamInQuery() returns false then the returned SeekableStream
     * will be newly opened each time, and should be closed after each use.
     *
     * @return a SeekableStream
     */
    private SeekableStream getSeekableStream() throws IOException {
        final SeekableStream result;
        if (reuseStreamInQuery()) {
            // if the stream points to an underlying file, only create the underlying seekable stream once
            if (seekableStream == null)
                seekableStream = SeekableStreamFactory.getInstance().getStreamFor(path, wrapper);
            result = seekableStream;
        } else {
            // we are not reusing the stream, so make a fresh copy each time we request it
            result = SeekableStreamFactory.getInstance().getStreamFor(path, wrapper);
        }

        return result;
    }

    /**
     * Are we attempting to reuse the underlying stream in query() calls?
     *
     * @return true if
     */
    private boolean reuseStreamInQuery() {
        return pathIsRegularFile;
    }

    @Override
    public void close() throws IOException {
        // close the seekable stream if that's necessary
        if (seekableStream != null) seekableStream.close();
    }

    /**
     * Return the sequence (chromosome/contig) names in this file, if known.
     *
     * @return list of strings of the contig names
     */
    @Override
    public List<String> getSequenceNames() {
        return !this.hasIndex() ? new ArrayList<>() : new ArrayList<>(index.getSequenceNames());
    }

    @Override
    public boolean hasIndex() {
        if (index == null && this.needCheckForIndex) {
            try {
                this.loadIndex();
            } catch (IOException e) {
                throw new TribbleException("Error loading index file: " + e.getMessage(), e);
            }
        }
        return index != null;
    }

    /**
     * @return true if the reader has an index, which means that it can be queried.
     */
    @Override
    public boolean isQueryable() {
        return hasIndex();
    }

    /**
     * read the header from the file
     *
     * @throws IOException throws an IOException if we can't open the file
     */
    private void readHeader() throws IOException {
        InputStream is = null;
        PositionalBufferedStream pbs = null;
        try {
            is = ParsingUtils.openInputStream(path, wrapper);
            if (IOUtil.hasBlockCompressedExtension(new URI(URLEncoder.encode(path, "UTF-8")))) {
                // TODO: TEST/FIX THIS! https://github.com/samtools/htsjdk/issues/944
                // TODO -- warning I don't think this can work, the buffered input stream screws up position
                is = new GZIPInputStream(new BufferedInputStream(is));
            }
            pbs = new PositionalBufferedStream(is);
            final SOURCE source = codec.makeSourceFromStream(pbs);
            header = codec.readHeader(source);
        } catch (Exception e) {
            throw new TribbleException.MalformedFeatureFile("Unable to parse header with error: " + e.getMessage(), path, e);
        } finally {
            if (pbs != null) pbs.close();
            else if (is != null) is.close();
        }
    }

    /**
     * Return an iterator to iterate over features overlapping the specified interval
     * <p/>
     * Note that TribbleIndexedFeatureReader only supports issuing and manipulating a single query
     * for each reader.  That is, the behavior of the following code is undefined:
     * <p/>
     * reader = new TribbleIndexedFeatureReader()
     * Iterator it1 = reader.query("x", 10, 20)
     * Iterator it2 = reader.query("x", 1000, 1010)
     * <p/>
     * As a consequence of this, the TribbleIndexedFeatureReader are also not thread-safe.
     *
     * @param chr   contig
     * @param start start position
     * @param end   end position
     * @return an iterator of records in this interval
     * @throws IOException
     */
    @Override
    public CloseableTribbleIterator<T> query(final String chr, final int start, final int end) throws IOException {

        if (!this.hasIndex()) {
            throw new TribbleException("Index not found for: " + path);
        }

        if (index.containsChromosome(chr)) {
            final List<Block> blocks = index.getBlocks(chr, start - 1, end);
            return new QueryIterator(chr, start, end, blocks);
        } else {
            return new EmptyIterator<>();
        }
    }

    /**
     * @return Return an iterator to iterate over the entire file
     * @throws IOException
     */
    @Override
    public CloseableTribbleIterator<T> iterator() throws IOException {
        return new WFIterator();
    }

    /**
     * Class to iterator over an entire file.
     */
    class WFIterator implements CloseableTribbleIterator<T> {
        private T currentRecord;
        private final SOURCE source;

        /**
         * Constructor for iterating over the entire file (seekableStream).
         *
         * @throws IOException
         */
        public WFIterator() throws IOException {
            final InputStream inputStream = ParsingUtils.openInputStream(path, wrapper);

            final PositionalBufferedStream pbs;
            if (IOUtil.hasBlockCompressedExtension(path)) {
                // Gzipped -- we need to buffer the GZIPInputStream methods as this class makes read() calls,
                // and seekableStream does not support single byte reads
                final InputStream is = new GZIPInputStream(new BufferedInputStream(inputStream, 512000));
                pbs = new PositionalBufferedStream(is, 1000);  // Small buffer as this is buffered already.
            } else {
                pbs = new PositionalBufferedStream(inputStream, 512000);
            }
            /*
             * The header was already read from the original source in the constructor; don't read it again, since some codecs keep state
             * about its initialization.  Instead, skip that part of the stream.
             */
            pbs.skip(header.getHeaderEnd());
            source = codec.makeSourceFromStream(pbs);
            readNextRecord();
        }

        @Override
        public boolean hasNext() {
            return currentRecord != null;
        }

        @Override
        public T next() {
            final T ret = currentRecord;
            try {
                readNextRecord();
            } catch (IOException e) {
                throw new RuntimeIOException("Unable to read the next record, the last record was at " +
                        ret.getContig() + ":" + ret.getStart() + "-" + ret.getEnd(), e);
            }
            return ret;
        }

        /**
         * Advance to the next record in the query interval.
         *
         * @throws IOException
         */
        private void readNextRecord() throws IOException {
            // for error reporting only
            final T previousRecord = currentRecord;
            currentRecord = null;

            while (!codec.isDone(source)) {
                final T f;
                try {
                    f = codec.decode(source);

                    if (f == null) {
                        continue;
                    }

                    currentRecord = f;
                    return;

                } catch (TribbleException e) {
                    e.setSource(path);
                    throw e;
                } catch (NumberFormatException e) {

                    final String error;
                    if (previousRecord == null) {
                        error = String.format("Error parsing %s at the first record", source);
                    } else {
                        error = String.format("Error parsing %s just after record at: %s:%d-%d",
                                source.toString(), previousRecord.getContig(), previousRecord.getStart(), previousRecord.getEnd());
                    }
                    throw new TribbleException.MalformedFeatureFile(error, path, e);
                }
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove is not supported in Iterators");
        }

        @Override
        public void close() {
            codec.close(source);
        }

        @Override
        public WFIterator iterator() {
            return this;
        }
    }

    /**
     * Iterator for a query interval
     */
    class QueryIterator implements CloseableTribbleIterator<T> {
        private final String chrAlias;

        private final String queryChr;
        private final int start;
        private final int end;
        private T currentRecord;
        private SOURCE source;
        private SeekableStream mySeekableStream;
        private Iterator<Block> blockIterator;

        public QueryIterator(final String chr, final int start, final int end, final List<Block> blocks) throws IOException {
            this.start = start;
            this.end = end;

            // For a meaningful error message when an exception is thrown in readNextRecord below.
            queryChr = chr;

            mySeekableStream = getSeekableStream();
            blockIterator = blocks.iterator();
            advanceBlock();
            readNextRecord();

            // The feature chromosome might not be the query chromosome, due to alias definitions.  We assume
            // the chromosome of the first record is correct and record it here.  This is not pretty.
            chrAlias = (currentRecord == null ? chr : currentRecord.getContig());
        }

        @Override
        public boolean hasNext() {
            return currentRecord != null;
        }

        @Override
        public T next() {
            final T ret = currentRecord;
            try {
                readNextRecord();
            } catch (IOException e) {
                throw new RuntimeIOException("Unable to read the next record, the last record was at " +
                        ret.getContig() + ":" + ret.getStart() + "-" + ret.getEnd(), e);
            }
            return ret;
        }

        private void advanceBlock() throws IOException {
            while (blockIterator != null && blockIterator.hasNext()) {
                final Block block = blockIterator.next();
                if (block.getSize() > 0) {
                    final int bufferSize = Math.min(2_000_000, block.getSize() > 100_000_000 ? 10_000_000 : (int) block.getSize());
                    source = codec.makeSourceFromStream(new PositionalBufferedStream(new BlockStreamWrapper(mySeekableStream, block), bufferSize));
                    // note we don't have to skip the header here as the block should never start in the header
                    return;
                }
            }

            // If we get here the blocks are exhausted, set reader to null
            if (source != null) {
                codec.close(source);
                source = null;
            }
        }

        /**
         * Advance to the next record in the query interval.
         *
         * @throws IOException
         */
        private void readNextRecord() throws IOException {
            final T previousRecord = currentRecord;

            if (source == null) {
                return;  // <= no more features to read
            }

            currentRecord = null;

            while (true) {   // Loop through blocks
                while (!codec.isDone(source)) {  // Loop through current block
                    final T f;
                    try {
                        f = codec.decode(source);
                        if (f == null) {
                            continue;   // Skip
                        }
                        if ((chrAlias != null && !f.getContig().equals(chrAlias)) || f.getStart() > end) {
                            if (blockIterator.hasNext()) {
                                advanceBlock();
                                continue;
                            } else {
                                return;    // Done
                            }
                        }
                        if (f.getEnd() < start) {
                            continue;   // Skip
                        }

                        currentRecord = f;     // Success
                        return;

                    } catch (TribbleException e) {
                        e.setSource(path);
                        throw e;
                    } catch (NumberFormatException e) {

                        final String error;
                        if (previousRecord == null) {
                            error = String.format("Error parsing %s at the first queried after %s:%d", source, this.chrAlias == null ? this.queryChr : this.chrAlias, this.start);
                        } else {
                            error = String.format("Error parsing %s just after record at: %s:%d-%d",
                                    source.toString(), previousRecord.getContig(), previousRecord.getStart(), previousRecord.getEnd());
                        }
                        throw new TribbleException.MalformedFeatureFile(error, path, e);
                    }
                }
                if (blockIterator != null && blockIterator.hasNext()) {
                    advanceBlock();   // Advance to next block
                } else {
                    return;   // No blocks left, we're done.
                }
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove is not supported.");
        }

        @Override
        public void close() {
            // Note that this depends on BlockStreamWrapper not actually closing the underlying stream
            codec.close(source);
            if (!reuseStreamInQuery()) {
                // if we are going to reuse the underlying stream we don't close the underlying stream.
                try {
                    mySeekableStream.close();
                } catch (IOException e) {
                    throw new TribbleException("Couldn't close seekable stream", e);
                }
            }
        }

        @Override
        public Iterator<T> iterator() {
            return this;
        }
    }

    /**
     * Wrapper around a SeekableStream that limits reading to the specified "block" of bytes.  Attempts to
     * read beyond the end of the block should return -1  (EOF).
     */
    static class BlockStreamWrapper extends InputStream {

        SeekableStream seekableStream;
        long maxPosition;

        BlockStreamWrapper(final SeekableStream seekableStream, final Block block) throws IOException {
            this.seekableStream = seekableStream;
            seekableStream.seek(block.getStartPosition());
            maxPosition = block.getEndPosition();
        }

        @Override
        public int read() throws IOException {
            return (seekableStream.position() > maxPosition) ? -1 : seekableStream.read();
        }

        @Override
        public int read(final byte[] bytes, final int off, final int len) throws IOException {
            // note the careful treatment here to ensure we can continue to
            // read very long > Integer sized blocks
            final long maxBytes = maxPosition - seekableStream.position();
            if (maxBytes <= 0) {
                return -1;
            }

            final int bytesToRead = (int) Math.min(len, Math.min(maxBytes, Integer.MAX_VALUE));
            return seekableStream.read(bytes, off, bytesToRead);
        }
    }
}
