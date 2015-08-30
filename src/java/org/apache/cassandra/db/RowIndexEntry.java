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
package org.apache.cassandra.db;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.rows.RangeTombstoneMarker;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.db.rows.UnfilteredSerializer;
import org.apache.cassandra.io.ISerializer;
import org.apache.cassandra.io.sstable.IndexInfo;
import org.apache.cassandra.io.sstable.format.Version;
import org.apache.cassandra.io.sstable.format.big.BigFormat;
import org.apache.cassandra.io.util.DataInputBuffer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.io.util.SequentialWriter;
import org.apache.cassandra.utils.ByteBufferUtil;

/**
 * Binary format of {@code RowIndexEntry} is defined as follows:
 * {@code
 * (long) position
 *  (int) serialized size of data that follows
 *  (int) DeletionTime.localDeletionTime
 * (long) DeletionTime.markedForDeletionAt
 *  (int) number of IndexInfo objects
 *    (*) serialized IndexInfo objects, see below
 * }
 * <p>
 * Each {@link IndexInfo} object is serialized as follows:
 * {@code
 *    (*) IndexInfo.firstName (ClusteringPrefix serializer, either Clustering.serializer.serialize or Slice.Bound.serializer.serialize)
 *    (*) IndexInfo.lastName (ClusteringPrefix serializer)
 * (long) IndexInfo.offset
 * (long) IndexInfo.width
 * (bool) IndexInfo.endOpenMarker != null              (if version.storeRows)
 *  (int) IndexInfo.endOpenMarker.localDeletionTime    (if version.storeRows && IndexInfo.endOpenMarker != null)
 * (long) IndexInfo.endOpenMarker.markedForDeletionAt  (if version.storeRows && IndexInfo.endOpenMarker != null)
 * }
 * </p>
 */
public class RowIndexEntry
{

    public final long position;

    public RowIndexEntry(long position)
    {
        this.position = position;
    }

    // Note that for the old layout, this will actually discard the cellname parts that are not strictly
    // part of the clustering prefix. Don't use this if that's not what you want.
    public static ISerializer<ClusteringPrefix> clusteringPrefixSerializer(final Version version, final SerializationHeader header)
    {
        if (!version.storeRows())
            throw new UnsupportedOperationException();

        return new ClusteringPrefixSerializer(version.correspondingMessagingVersion(), header.clusteringTypes());
    }

    public void serialize(ByteBuffer out)
    {
        out.putLong(position);
        out.putInt(0);
    }

    void serialize(Version version, DataOutputPlus out) throws IOException
    {
        out.writeLong(position);
        out.writeInt(0);
    }

    public int nativeSize()
    {
        return 12;
    }

    public static RowIndexEntry buildIndex(long position, DeletionTime deletionTime,
                                           UnfilteredRowIterator iterator, SequentialWriter output,
                                           SerializationHeader header, Version version) throws IOException
    {
        assert !iterator.isEmpty() && version.storeRows();
        assert deletionTime != null;

        Builder builder = new Builder(position, deletionTime,
                                      iterator, output, header, version.correspondingMessagingVersion());
        return builder.build();
    }

    /**
     * @return true if this index entry contains the row-level tombstone and column summary.  Otherwise,
     * caller should fetch these from the row header.
     */
    public boolean isIndexed()
    {
        return columnsCount() > 0;
    }

    public DeletionTime deletionTime()
    {
        throw new UnsupportedOperationException();
    }

    public int columnsCount()
    {
        return 0;
    }

    public IndexInfo indexInfo(int indexIdx)
    {
        throw new IndexOutOfBoundsException();
    }

    /**
     * The index of the IndexInfo in which a scan starting with @name should begin.
     *
     * @param name name to search for
     * @param comparator the comparator to use
     * @param reversed whether or not the search is reversed, i.e. we scan forward or backward from name
     * @param lastIndex where to start the search from in indexList
     *
     * @return int index
     */
    public int indexOf(ClusteringPrefix name, ClusteringComparator comparator, boolean reversed, int lastIndex)
    {
        IndexInfo target = new IndexInfo(name, name, 0, 0, null);
        /*
        Take the example from the unit test, and say your index looks like this:
        [0..5][10..15][20..25]
        and you look for the slice [13..17].

        When doing forward slice, we are doing a binary search comparing 13 (the start of the query)
        to the lastName part of the index slot. You'll end up with the "first" slot, going from left to right,
        that may contain the start.

        When doing a reverse slice, we do the same thing, only using as a start column the end of the query,
        i.e. 17 in this example, compared to the firstName part of the index slots.  bsearch will give us the
        first slot where firstName > start ([20..25] here), so we subtract an extra one to get the slot just before.
        */
        int size = columnsCount();
        int startIdx = 0;
        int endIdx = size;
        if (reversed)
        {
            if (lastIndex < size - 1)
            {
                endIdx = lastIndex + 1;
            }
        }
        else
        {
            if (lastIndex > 0)
            {
                startIdx = lastIndex;
            }
        }
        int index = binarySearch(target, comparator.indexComparator(reversed), startIdx, endIdx);
        return index < 0 ? -index - (reversed ? 2 : 1) : index;
    }

    private int binarySearch(IndexInfo key, Comparator<IndexInfo> c,
                             int fromIndex, int toIndex) {
        int low = fromIndex;
        int high = toIndex - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            IndexInfo midVal = indexInfo(mid);
            int cmp = c.compare(midVal, key);

            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid; // key found
        }
        return -(low + 1);  // key not found
    }

    public static class Serializer
    {
        private final Version version;
        private final SerializationHeader header;

        public Serializer(Version version, SerializationHeader header)
        {
            this.version = version;
            this.header = header;
        }

        public void serialize(RowIndexEntry rie, DataOutputPlus out) throws IOException
        {
            assert BigFormat.latestVersion.equals(version);

            rie.serialize(version, out);
        }

        public RowIndexEntry deserialize(DataInputPlus in) throws IOException
        {
            long position = in.readLong();

            int size = in.readInt();
            if (size > 0)
            {
                if (nativeBinaryCompatible(version))
                {
                    ByteBuffer buffer = ByteBuffer.allocate(size);
                    in.readFully(buffer.array());
                    return new IndexedEntry(clusteringPrefixSerializer(version, header), position, buffer, header);
                }

                // convert from old format - TODO untested code, add upgrade tests (or are there some??)

                DataOutputBuffer output = new DataOutputBuffer(4096);

                DeletionTime deletionTime = DeletionTime.serializer.deserialize(in);
                int entries = in.readInt();

                DeletionTime.serializer.serialize(deletionTime, output);
                output.writeInt(entries);

                IndexInfo.Serializer idxDeserializer = IndexInfo.indexSerializer(version);
                ISerializer<ClusteringPrefix> prefixSer = clusteringPrefixSerializer(BigFormat.latestVersion, header);
                for (int i = 0; i < entries; i++)
                {
                    IndexInfo info = idxDeserializer.deserialize(in, header);
                    IndexInfo.Serializer.serialize(info, output, header, BigFormat.latestVersion, prefixSer);
                }

                return new IndexedEntry(clusteringPrefixSerializer(BigFormat.latestVersion, header), position, output.buffer(), header);
            }
            else
            {
                return new RowIndexEntry(position);
            }
        }

        public static void skip(DataInput in) throws IOException
        {
            in.readLong();
            skipPromotedIndex(in);
        }

        public static void skipPromotedIndex(DataInput in) throws IOException
        {
            int size = in.readInt();
            if (size <= 0)
                return;

            FileUtils.skipBytesFully(in, size);
        }
    }

    /**
     * An entry in the row index for a row whose columns are indexed.
     */
    private static class IndexedEntry extends RowIndexEntry
    {
        private final SerializationHeader header;
        private final ISerializer<ClusteringPrefix> clusteringSerializer;
        private final ByteBuffer buffer;
        private final int[] indexInfoOffsets;

        private int currentIndex = -1;
        private IndexInfo currentInfo;

        private IndexedEntry(ISerializer<ClusteringPrefix> clusteringSerializer, long position, ByteBuffer buffer, SerializationHeader header)
        {
            super(position);
            this.header = header;
            this.clusteringSerializer = clusteringSerializer;
            this.buffer = buffer;
            this.indexInfoOffsets = new int[columnsCount()];
        }

        @Override
        public DeletionTime deletionTime()
        {
            return DeletionTime.serializer.deserialize(buffer, 0);
        }

        @Override
        public int columnsCount()
        {
            return buffer.getInt((int) DeletionTime.serializer.serializedSize(null));
        }

        public IndexInfo indexInfo(int indexIdx)
        {
            // This method is often called with the same indexIdx argument.
            // (see org.apache.cassandra.db.columniterator.AbstractSSTableIterator.IndexState.currentIndex())
            if (indexIdx == currentIndex && currentInfo != null)
                return currentInfo;

            ByteBuffer buf = buffer.duplicate();

            try
            {
                DataInputBuffer input = new DataInputBuffer(buf, false);

                int offset = indexInfoOffsets[indexIdx];
                if (offset > 0)
                {
                    // we already know the offset of the requested IndexInfo, so just "seek" and deserialize
                    buf.position(offset);
                }
                else
                {
                    int i = 0;
                    while (i < indexIdx && indexInfoOffsets[i] != 0)
                        i++;

                    // "seek" to last known IndexInfo
                    if (i == 0)
                        offset = (int) (TypeSizes.sizeof(0)/*columnsCount*/ + DeletionTime.serializer.serializedSize(null));
                    else
                    {
                        i--;
                        offset = indexInfoOffsets[i];
                    }
                    buf.position(offset);

                    // need to read through all IndexInfo objects until we reach the requested one
                    for (; ; i++)
                    {
                        indexInfoOffsets[i] = buf.position();

                        if (i == indexIdx)
                            break;

                        IndexInfo.latestVersionSerializer.skip(input, header, clusteringSerializer);
                    }
                }

                IndexInfo info = IndexInfo.latestVersionSerializer.deserialize(input, clusteringSerializer);

                if (indexInfoOffsets.length > indexIdx + 1)
                    // we know the offset of the next IndexInfo - so store it
                    indexInfoOffsets[indexIdx + 1] = buf.position();

                currentIndex = indexIdx;
                currentInfo = info;
                return info;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void serialize(ByteBuffer out)
        {
            out.putLong(position);
            out.putInt(buffer.limit());
            out.put(buffer);
        }

        void serialize(Version version, DataOutputPlus out) throws IOException
        {
            out.writeLong(position);

            // note: can only serialize with latest version

            out.writeInt(buffer.limit());
            out.write(buffer.array(), 0, buffer.limit());
        }

        public int nativeSize()
        {
            return super.nativeSize() + buffer.limit();
        }
    }



    /**
     * Help to create an index for a column family based on size of columns,
     * and write said columns to disk.
     */
    private static class Builder
    {
        private final ISerializer<ClusteringPrefix> clusteringSerializer;
        private final UnfilteredRowIterator iterator;
        private final SequentialWriter writer;
        private final SerializationHeader header;
        private final int version;

        private final long initialPosition;
        private long startPosition = -1;

        private int written;

        private ClusteringPrefix firstClustering;
        private ClusteringPrefix lastClustering;

        private DeletionTime openMarker;

        private final long position;
        private final DeletionTime deletionTime;
        private DataOutputBuffer bufferOut;
        private int columnsIndexCount;
        private IndexInfo firstIndex;

        Builder(long position,
                DeletionTime deletionTime,
                UnfilteredRowIterator iterator,
                SequentialWriter writer,
                SerializationHeader header,
                int version)
        {
            this.position = position;
            this.deletionTime = deletionTime;

            this.iterator = iterator;
            this.writer = writer;
            this.header = header;
            this.version = version;

            this.initialPosition = writer.getFilePointer();

            clusteringSerializer = clusteringPrefixSerializer(BigFormat.latestVersion, header);
        }

        private void writePartitionHeader(UnfilteredRowIterator iterator) throws IOException
        {
            ByteBufferUtil.writeWithShortLength(iterator.partitionKey().getKey(), writer);
            DeletionTime.serializer.serialize(iterator.partitionLevelDeletion(), writer);
            if (header.hasStatic())
                UnfilteredSerializer.serializer.serialize(iterator.staticRow(), header, writer, version);
        }

        public RowIndexEntry build() throws IOException
        {
            writePartitionHeader(iterator);

            while (iterator.hasNext())
                add(iterator.next());

            return close();
        }

        private long currentPosition()
        {
            return writer.getFilePointer() - initialPosition;
        }

        private void addIndexBlock() throws IOException
        {
            // A RowIndexEntry.IndexedEntry (that with IndexInfo objects) is only written,
            // if there are at least TWO IndexInfo objects. We only need a bufferOut for
            // such an RowIndexEntry.IndexedEntry. So prevent allocating bufferOut if it is
            // not necessary.

            IndexInfo cIndexInfo = new IndexInfo(firstClustering,
                                                 lastClustering,
                                                 startPosition,
                                                 currentPosition() - startPosition,
                                                 openMarker);
            if (bufferOut == null)
            {
                if (firstIndex == null)
                    firstIndex = cIndexInfo;
                else
                {
                    bufferOut = new DataOutputBuffer(4096);

                    DeletionTime.serializer.serialize(deletionTime, bufferOut); // placeholder
                    bufferOut.writeInt(0); // placeholder

                    IndexInfo.Serializer.serialize(firstIndex, bufferOut, header,
                                                   BigFormat.latestVersion, clusteringSerializer);
                    firstIndex = null;
                }
            }
            if (bufferOut != null)
                IndexInfo.Serializer.serialize(cIndexInfo, bufferOut, header,
                                               BigFormat.latestVersion, clusteringSerializer);

            columnsIndexCount ++;
            firstClustering = null;
        }

        private void add(Unfiltered unfiltered) throws IOException
        {
            if (firstClustering == null)
            {
                // Beginning of an index block. Remember the start and position
                firstClustering = unfiltered.clustering();
                startPosition = currentPosition();
            }

            UnfilteredSerializer.serializer.serialize(unfiltered, header, writer, version);
            lastClustering = unfiltered.clustering();
            ++written;

            if (unfiltered.kind() == Unfiltered.Kind.RANGE_TOMBSTONE_MARKER)
            {
                RangeTombstoneMarker marker = (RangeTombstoneMarker)unfiltered;
                openMarker = marker.isOpen(false) ? marker.openDeletionTime(false) : null;
            }

            // if we hit the column index size that we have to index after, go ahead and index it.
            if (currentPosition() - startPosition >= DatabaseDescriptor.getColumnIndexSize())
                addIndexBlock();

        }

        private RowIndexEntry close() throws IOException
        {
            UnfilteredSerializer.serializer.writeEndOfPartition(writer);

            // It's possible we add no rows, just a top level deletion
            if (written == 0)
                return new RowIndexEntry(position);

            // the last column may have fallen on an index boundary already.  if not, index it explicitly.
            if (firstClustering != null)
                addIndexBlock();

            assert columnsIndexCount > 0;

            // we only consider the columns summary when determining whether to create an IndexedEntry,
            // since if there are insufficient columns to be worth indexing we're going to seek to
            // the beginning of the row anyway, so we might as well read the tombstone there as well.
            if (columnsIndexCount > 1)
            {
                ByteBuffer buf = bufferOut.buffer();
                buf.putInt((int) DeletionTime.serializer.serializedSize(null), columnsIndexCount);
                return new IndexedEntry(clusteringSerializer, position, buf, header);
            }
            else
                return new RowIndexEntry(position);
        }
    }

    static boolean nativeBinaryCompatible(Version version)
    {
        return BigFormat.latestVersion.equals(version) ||
               (BigFormat.latestVersion.storeRows() == version.storeRows() &&
                BigFormat.latestVersion.correspondingMessagingVersion() == version.correspondingMessagingVersion());
    }

    private static final class ClusteringPrefixSerializer implements ISerializer<ClusteringPrefix>
    {
        private final int messagingVersion;
        private final List<AbstractType<?>> clusteringTypes;

        ClusteringPrefixSerializer(int messagingVersion, List<AbstractType<?>> clusteringTypes)
        {
            this.messagingVersion = messagingVersion;
            this.clusteringTypes = clusteringTypes;
        }

        public void serialize(ClusteringPrefix clustering, DataOutputPlus out) throws IOException
        {
            ClusteringPrefix.serializer.serialize(clustering, out, messagingVersion, clusteringTypes);
        }

        public ClusteringPrefix deserialize(DataInputPlus in) throws IOException
        {
            return ClusteringPrefix.serializer.deserialize(in, messagingVersion, clusteringTypes);
        }

        public long serializedSize(ClusteringPrefix clustering)
        {
            return ClusteringPrefix.serializer.serializedSize(clustering, messagingVersion, clusteringTypes);
        }
    }
}
