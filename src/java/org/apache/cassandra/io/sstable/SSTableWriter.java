/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.AbstractCompactedRow;
import org.apache.cassandra.db.compaction.PrecompactedRow;
import org.apache.cassandra.db.index.SecondaryIndex;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.compress.CompressedSequentialWriter;
import org.apache.cassandra.io.sstable.SSTableWriterListener.Source;
import org.apache.cassandra.io.util.*;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.FilterFactory;
import org.apache.cassandra.utils.IFilter;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.StreamingHistogram;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSTableWriter extends SSTable
{
    private static final Logger logger = LoggerFactory.getLogger(SSTableWriter.class);

    // not very random, but the only value that can't be mistaken for a legal column-name length
    public static final int END_OF_ROW = 0x0000;

    private IndexWriter iwriter;
    private SegmentedFile.Builder dbuilder;
    private final SequentialWriter dataFile;
    private DecoratedKey lastWrittenKey;
    private FileMark dataMark;
    private final SSTableMetadata.Collector sstableMetadataCollector;

    private final List<SSTableWriterListener> listeners;

    public SSTableWriter(String filename, long keyCount, Set<SecondaryIndex> indexes)
    {
        this(filename,
             keyCount,
             Schema.instance.getCFMetaData(Descriptor.fromFilename(filename)),
             StorageService.getPartitioner(),
             SSTableMetadata.createCollector(Schema.instance.getCFMetaData(Descriptor.fromFilename(filename)).comparator),
             indexes);
    }

    private static Set<Component> components(CFMetaData metadata, Set<SecondaryIndex> indexes)
    {
        Set<Component> components = new HashSet<Component>(Arrays.asList(Component.DATA,
                                                                         Component.PRIMARY_INDEX,
                                                                         Component.STATS,
                                                                         Component.SUMMARY,
                                                                         Component.TOC));

        if (metadata.getBloomFilterFpChance() < 1.0)
            components.add(Component.FILTER);

        if (metadata.compressionParameters().sstableCompressionClass != null)
        {
            components.add(Component.COMPRESSION_INFO);
        }
        else
        {
            // it would feel safer to actually add this component later in maybeWriteDigest(),
            // but the components are unmodifiable after construction
            components.add(Component.DIGEST);
            components.add(Component.CRC);
        }

        for (SecondaryIndex secondaryIndex : indexes)
            components.addAll(secondaryIndex.getIndexComponents());

        return components;
    }

    public SSTableWriter(String filename,
                         long keyCount,
                         CFMetaData metadata,
                         IPartitioner<?> partitioner,
                         SSTableMetadata.Collector sstableMetadataCollector,
                         Set<SecondaryIndex> indexes)
    {
        this(filename, keyCount, metadata, partitioner, sstableMetadataCollector, SSTableWriterListener.Source.COMPACTION, indexes);
    }

    public SSTableWriter(String filename,
        long keyCount,
        CFMetaData metadata,
        IPartitioner<?> partitioner,
        SSTableMetadata.Collector sstableMetadataCollector,
        Source source,
        Set<SecondaryIndex> indexes)
    {
        super(Descriptor.fromFilename(filename),
              components(metadata, indexes),
              metadata,
              partitioner);
        iwriter = new IndexWriter(keyCount);

        if (compression)
        {
            dbuilder = SegmentedFile.getCompressedBuilder();
            dataFile = CompressedSequentialWriter.open(getFilename(),
                                                       descriptor.filenameFor(Component.COMPRESSION_INFO),
                                                       !metadata.populateIoCacheOnFlush(),
                                                       metadata.compressionParameters(),
                                                       sstableMetadataCollector);
        }
        else
        {
            dbuilder = SegmentedFile.getBuilder(DatabaseDescriptor.getDiskAccessMode());
            dataFile = SequentialWriter.open(new File(getFilename()), !metadata.populateIoCacheOnFlush());
            dataFile.setDataIntegrityWriter(DataIntegrityMetadata.checksumWriter(descriptor));
        }

        this.sstableMetadataCollector = sstableMetadataCollector;
        List<SSTableWriterListener> listeners = new ArrayList<>();
        for (SecondaryIndex si : indexes)
        {
            SSTableWriterListener listener = si.getWriterListener(descriptor, source);
            if (listener != null)
            {
                listener.begin();
                listeners.add(listener);
            }
        }
        this.listeners = ImmutableList.copyOf(listeners);
    }

    public void mark()
    {
        dataMark = dataFile.mark();
        iwriter.mark();
    }

    public void resetAndTruncate()
    {
        dataFile.resetAndTruncate(dataMark);
        iwriter.resetAndTruncate();
    }

    /**
     * Perform sanity checks on @param decoratedKey and @return the position in the data file before any data is written
     */
    private Pair<Long, Long> beforeAppend(DecoratedKey decoratedKey)
    {
        assert decoratedKey != null : "Keys must not be null"; // empty keys ARE allowed b/c of indexed column values
        if (lastWrittenKey != null && lastWrittenKey.compareTo(decoratedKey) >= 0)
            throw new RuntimeException("Last written key " + lastWrittenKey + " >= current key " + decoratedKey + " writing into " + getFilename());
        long dataPosition = (lastWrittenKey == null) ? 0 : dataFile.getFilePointer();
        long indexPosition = (lastWrittenKey == null) ? 0 : iwriter.getFilePointer();
        return Pair.create(indexPosition, dataPosition);
    }

    private void afterAppend(DecoratedKey decoratedKey, long dataPosition, RowIndexEntry index)
    {
        lastWrittenKey = decoratedKey;
        last = lastWrittenKey;
        if (first == null)
            first = lastWrittenKey;

        if (logger.isTraceEnabled())
            logger.trace("wrote " + decoratedKey + " at " + dataPosition);
        iwriter.append(decoratedKey, index);
        dbuilder.addPotentialBoundary(dataPosition);
    }

    /**
     * @param row
     * @return null if the row was compacted away entirely; otherwise, the PK index entry for this row
     */
    public RowIndexEntry append(AbstractCompactedRow row)
    {
        Pair<Long, Long> currentPositions = beforeAppend(row.key);
        RowIndexEntry entry;
        try
        {
            entry = row.write(currentPositions, dataFile.stream, listeners);
            if (entry == null)
                return null;
        }
        catch (IOException e)
        {
            throw new FSWriteError(e, dataFile.getPath());
        }
        sstableMetadataCollector.update(dataFile.getFilePointer() - currentPositions.right, row.columnStats());
        afterAppend(row.key, currentPositions.right, entry);
        return entry;
    }

    public void append(DecoratedKey decoratedKey, ColumnFamily cf)
    {
        if (decoratedKey.key.remaining() > FBUtilities.MAX_UNSIGNED_SHORT)
        {
            logger.error("Key size {} exceeds maximum of {}, skipping row",
                         decoratedKey.key.remaining(),
                         FBUtilities.MAX_UNSIGNED_SHORT);
            return;
        }

        append(new PrecompactedRow(decoratedKey, cf));
    }

    /**
     * @throws IOException if a read from the DataInput fails
     * @throws FSWriteError if a write to the dataFile fails
     */
    public long appendFromStream(DecoratedKey key, CFMetaData metadata, DataInput in, Descriptor.Version version) throws IOException
    {
        Pair<Long, Long> currentPositions = beforeAppend(key);

        ColumnStats.MaxTracker<Long> maxTimestampTracker = new ColumnStats.MaxTracker<>(Long.MAX_VALUE);
        ColumnStats.MinTracker<Long> minTimestampTracker = new ColumnStats.MinTracker<>(Long.MIN_VALUE);
        ColumnStats.MaxTracker<Integer> maxDeletionTimeTracker = new ColumnStats.MaxTracker<>(Integer.MAX_VALUE);
        List<ByteBuffer> minColumnNames = Collections.emptyList();
        List<ByteBuffer> maxColumnNames = Collections.emptyList();
        StreamingHistogram tombstones = new StreamingHistogram(TOMBSTONE_HISTOGRAM_BIN_SIZE);
        ColumnFamily cf = ArrayBackedSortedColumns.factory.create(metadata);

        // skip row size for version < ja
        if (version.hasRowSizeAndColumnCount)
            FileUtils.skipBytesFully(in, 8);

        cf.delete(DeletionTime.serializer.deserialize(in));

        for (SSTableWriterListener listener : listeners)
            listener.startRow(key, currentPositions.left);
        ColumnIndex.Builder columnIndexer = new ColumnIndex.Builder(cf, key.key, dataFile.stream, listeners);

        // read column count for version < ja
        int columnCount = Integer.MAX_VALUE;
        if (version.hasRowSizeAndColumnCount)
            columnCount = in.readInt();

        if (cf.deletionInfo().getTopLevelDeletion().localDeletionTime < Integer.MAX_VALUE)
        {
            tombstones.update(cf.deletionInfo().getTopLevelDeletion().localDeletionTime);
            maxDeletionTimeTracker.update(cf.deletionInfo().getTopLevelDeletion().localDeletionTime);
            minTimestampTracker.update(cf.deletionInfo().getTopLevelDeletion().markedForDeleteAt);
            maxTimestampTracker.update(cf.deletionInfo().getTopLevelDeletion().markedForDeleteAt);
        }

        Iterator<RangeTombstone> rangeTombstoneIterator = cf.deletionInfo().rangeIterator();
        while (rangeTombstoneIterator.hasNext())
        {
            RangeTombstone rangeTombstone = rangeTombstoneIterator.next();
            tombstones.update(rangeTombstone.getLocalDeletionTime());
            minTimestampTracker.update(rangeTombstone.minTimestamp());
            maxTimestampTracker.update(rangeTombstone.maxTimestamp());
            maxDeletionTimeTracker.update(rangeTombstone.getLocalDeletionTime());
            minColumnNames = ColumnNameHelper.minComponents(minColumnNames, rangeTombstone.min, metadata.comparator);
            maxColumnNames = ColumnNameHelper.maxComponents(maxColumnNames, rangeTombstone.max, metadata.comparator);
        }

        Iterator<OnDiskAtom> iter = metadata.getOnDiskIterator(in, columnCount, ColumnSerializer.Flag.PRESERVE_SIZE, Integer.MIN_VALUE, version);
        try
        {
            while (iter.hasNext())
            {
                OnDiskAtom atom = iter.next();
                if (atom == null)
                    break;
                if (atom instanceof CounterColumn)
                    atom = ((CounterColumn) atom).markLocalToBeCleared();

                int deletionTime = atom.getLocalDeletionTime();
                if (deletionTime < Integer.MAX_VALUE)
                {
                    tombstones.update(deletionTime);
                }
                minTimestampTracker.update(atom.minTimestamp());
                maxTimestampTracker.update(atom.maxTimestamp());
                minColumnNames = ColumnNameHelper.minComponents(minColumnNames, atom.name(), metadata.comparator);
                maxColumnNames = ColumnNameHelper.maxComponents(maxColumnNames, atom.name(), metadata.comparator);
                maxDeletionTimeTracker.update(atom.getLocalDeletionTime());

                columnIndexer.add(atom); // This write the atom on disk too
            }

            columnIndexer.maybeWriteEmptyRowHeader();
            dataFile.stream.writeShort(END_OF_ROW);
        }
        catch (IOException e)
        {
            throw new FSWriteError(e, dataFile.getPath());
        }

        sstableMetadataCollector.updateMinTimestamp(minTimestampTracker.get());
        sstableMetadataCollector.updateMaxTimestamp(maxTimestampTracker.get());
        sstableMetadataCollector.updateMaxLocalDeletionTime(maxDeletionTimeTracker.get());
        sstableMetadataCollector.addRowSize(dataFile.getFilePointer() - currentPositions.right);
        sstableMetadataCollector.addColumnCount(columnIndexer.writtenAtomCount());
        sstableMetadataCollector.mergeTombstoneHistogram(tombstones);
        sstableMetadataCollector.updateMinColumnNames(minColumnNames);
        sstableMetadataCollector.updateMaxColumnNames(maxColumnNames);
        afterAppend(key, currentPositions.right, RowIndexEntry.create(currentPositions.right, cf.deletionInfo().getTopLevelDeletion(), columnIndexer.build()));
        return currentPositions.right;
    }

    /**
     * After failure, attempt to close the index writer and data file before deleting all temp components for the sstable
     */
    public void abort()
    {
        assert descriptor.temporary;
        iwriter.abort();
        dataFile.abort();

        Set<Component> components = SSTable.componentsFor(descriptor);
        try
        {
            if (!components.isEmpty())
                SSTable.delete(descriptor, components);
        }
        catch (FSWriteError e)
        {
            logger.error(String.format("Failed deleting temp components for %s", descriptor), e);
            throw e;
        }
    }

    public SSTableReader closeAndOpenReader()
    {
        return closeAndOpenReader(System.currentTimeMillis());
    }

    public SSTableReader closeAndOpenReader(long maxDataAge)
    {
        Pair<Descriptor, SSTableMetadata> p = close();
        Descriptor newdesc = p.left;
        SSTableMetadata sstableMetadata = p.right;

        // finalize in-memory state for the reader
        SegmentedFile ifile = iwriter.builder.complete(newdesc.filenameFor(SSTable.COMPONENT_INDEX));
        SegmentedFile dfile = dbuilder.complete(newdesc.filenameFor(SSTable.COMPONENT_DATA));
        SSTableReader sstable = SSTableReader.internalOpen(newdesc,
                                                           components,
                                                           metadata,
                                                           partitioner,
                                                           ifile,
                                                           dfile,
                                                           iwriter.summary.build(partitioner),
                                                           iwriter.bf,
                                                           maxDataAge,
                                                           sstableMetadata);
        sstable.first = getMinimalKey(first);
        sstable.last = getMinimalKey(last);
        // try to save the summaries to disk
        SSTableReader.saveSummary(sstable, iwriter.builder, dbuilder);
        iwriter = null;
        dbuilder = null;
        return sstable;
    }

    // Close the writer and return the descriptor to the new sstable and it's metadata
    public Pair<Descriptor, SSTableMetadata> close()
    {
        // index and filter
        iwriter.close();
        // main data, close will truncate if necessary
        dataFile.close();

        complete();

        // write sstable statistics
        SSTableMetadata sstableMetadata = sstableMetadataCollector.finalizeMetadata(partitioner.getClass().getCanonicalName(),
                                                                                    metadata.getBloomFilterFpChance());
        writeMetadata(descriptor, sstableMetadata, sstableMetadataCollector.ancestors);

        // save the table of components
        SSTable.appendTOC(descriptor, components);

        // remove the 'tmp' marker from all components
        return Pair.create(rename(descriptor, components), sstableMetadata);
    }

    public void complete()
    {
        for (SSTableWriterListener listener : listeners)
            listener.complete();
    }

    private static void writeMetadata(Descriptor desc, SSTableMetadata sstableMetadata,  Set<Integer> ancestors)
    {
        SequentialWriter out = SequentialWriter.open(new File(desc.filenameFor(SSTable.COMPONENT_STATS)), true);
        try
        {
            SSTableMetadata.serializer.serialize(sstableMetadata, ancestors, out.stream);
        }
        catch (IOException e)
        {
            out.abort();
            throw new FSWriteError(e, out.getPath());
        }
        out.close();
    }

    static Descriptor rename(Descriptor tmpdesc, Set<Component> components)
    {
        Descriptor newdesc = tmpdesc.asTemporary(false);
        rename(tmpdesc, newdesc, components);
        return newdesc;
    }

    public static void rename(Descriptor tmpdesc, Descriptor newdesc, Set<Component> components)
    {
        for (Component component : Sets.difference(components, Sets.newHashSet(Component.DATA, Component.SUMMARY)))
        {
            String tmpComponentPath = tmpdesc.filenameFor(component);

            // if current component is an SI it doesn't necessary mean that it's going to be present for
            // given SSTable because indexes are build based on the data contained by SSTable but we still need to
            // register all of the index components upfront, so we have to check existence here instead of failing rename.
            if (component.type.equals(Component.Type.SECONDARY_INDEX) && !new File(tmpComponentPath).exists())
                continue;

            FileUtils.renameWithConfirm(tmpComponentPath, newdesc.filenameFor(component));
        }

        // do -Data last because -Data present should mean the sstable was completely renamed before crash
        FileUtils.renameWithConfirm(tmpdesc.filenameFor(Component.DATA), newdesc.filenameFor(Component.DATA));

        // rename it without confirmation because summary can be available for loadNewSSTables but not for closeAndOpenReader
        FileUtils.renameWithOutConfirm(tmpdesc.filenameFor(Component.SUMMARY), newdesc.filenameFor(Component.SUMMARY));
    }

    public long getFilePointer()
    {
        return dataFile.getFilePointer();
    }

    public long getOnDiskFilePointer()
    {
        return dataFile.getOnDiskFilePointer();
    }

    /**
     * Encapsulates writing the index and filter for an SSTable. The state of this object is not valid until it has been closed.
     */
    class IndexWriter implements Closeable
    {
        private final SequentialWriter indexFile;
        public final SegmentedFile.Builder builder;
        public final IndexSummaryBuilder summary;
        public final IFilter bf;
        private FileMark mark;

        IndexWriter(long keyCount)
        {
            indexFile = SequentialWriter.open(new File(descriptor.filenameFor(SSTable.COMPONENT_INDEX)),
                                              !metadata.populateIoCacheOnFlush());
            builder = SegmentedFile.getBuilder(DatabaseDescriptor.getIndexAccessMode());
            summary = new IndexSummaryBuilder(keyCount, metadata.getIndexInterval());
            bf = FilterFactory.getFilter(keyCount, metadata.getBloomFilterFpChance(), true);
        }

        public void append(DecoratedKey key, RowIndexEntry indexEntry)
        {
            bf.add(key.key);
            long indexPosition = indexFile.getFilePointer();
            try
            {
                ByteBufferUtil.writeWithShortLength(key.key, indexFile.stream);
                RowIndexEntry.serializer.serialize(indexEntry, indexFile.stream);
            }
            catch (IOException e)
            {
                throw new FSWriteError(e, indexFile.getPath());
            }

            if (logger.isTraceEnabled())
                logger.trace("wrote index entry: " + indexEntry + " at " + indexPosition);

            summary.maybeAddEntry(key, indexPosition);
            builder.addPotentialBoundary(indexPosition);
        }

        /**
         * Closes the index and bloomfilter, making the public state of this writer valid for consumption.
         */
        public void close()
        {
            if (components.contains(Component.FILTER))
            {
                String path = descriptor.filenameFor(SSTable.COMPONENT_FILTER);
                try
                {
                    // bloom filter
                    FileOutputStream fos = new FileOutputStream(path);
                    DataOutputStream stream = new DataOutputStream(fos);
                    FilterFactory.serialize(bf, stream);
                    stream.flush();
                    fos.getFD().sync();
                    stream.close();
                }
                catch (IOException e)
                {
                    throw new FSWriteError(e, path);
                }
            }

            // index
            long position = indexFile.getFilePointer();
            indexFile.close(); // calls force
            FileUtils.truncate(indexFile.getPath(), position);
        }

        public void abort()
        {
            indexFile.abort();
            bf.close();
        }

        public void mark()
        {
            mark = indexFile.mark();
        }

        public void resetAndTruncate()
        {
            // we can't un-set the bloom filter addition, but extra keys in there are harmless.
            // we can't reset dbuilder either, but that is the last thing called in afterappend so
            // we assume that if that worked then we won't be trying to reset.
            indexFile.resetAndTruncate(mark);
        }

        public long getFilePointer()
        {
            return indexFile.getFilePointer();
        }

        @Override
        public String toString()
        {
            return "IndexWriter(" + descriptor + ")";
        }
    }

    private static ColumnFamilyStore getColumnFamilyStore(CFMetaData metadata)
    {
        try
        {
            // there could be situation when memtable is flushed for ks/cf which has already
            // been removed from schema, so we need to handle IllegalArgumentException case.
            return Keyspace.open(metadata.ksName).getColumnFamilyStore(metadata.cfId);
        }
        catch (Exception e)
        {
            logger.error("Failed to retrieve CFS for {}.{}", metadata.ksName, metadata.cfName, e);
        }

        return null;
    }
}
