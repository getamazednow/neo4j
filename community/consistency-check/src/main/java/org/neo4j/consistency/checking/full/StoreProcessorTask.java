/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.full;

import org.neo4j.consistency.checking.cache.CacheAccess;
import org.neo4j.consistency.checking.full.QueueDistribution.QueueDistributor;
import org.neo4j.consistency.statistics.Statistics;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.StoreAccess;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;

import static java.lang.String.format;

public class StoreProcessorTask<R extends AbstractBaseRecord> extends ConsistencyCheckerTask
{
    private final RecordStore<R> store;
    private final StoreProcessor processor;
    private final ProgressListener progressListener;
    private final StoreAccess storeAccess;
    private final CacheAccess cacheAccess;
    private final QueueDistribution distribution;
    private final PageCacheTracer pageCacheTracer;

    StoreProcessorTask( String name, Statistics statistics, int threads, RecordStore<R> store, StoreAccess storeAccess,
            String builderPrefix, ProgressMonitorFactory.MultiPartBuilder builder, CacheAccess cacheAccess,
            StoreProcessor processor, QueueDistribution distribution, PageCacheTracer pageCacheTracer )
    {
        super( name, statistics, threads );
        this.store = store;
        this.storeAccess = storeAccess;
        this.cacheAccess = cacheAccess;
        this.processor = processor;
        this.distribution = distribution;
        this.pageCacheTracer = pageCacheTracer;
        this.progressListener = builder.progressForPart( name +
                indexedPartName( store.getStorageFile().getName(), builderPrefix ), store.getHighId() );
    }

    private String indexedPartName( String storeFileName, String prefix )
    {
        return prefix.isEmpty() ? format( "%s_pass_%s", storeFileName, prefix ) : "_";
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public void run()
    {
        statistics.reset();
        beforeProcessing( processor );
        try
        {
            if ( processor.getStage().getCacheSlotSizes().length > 0 )
            {
                cacheAccess.setCacheSlotSizes( processor.getStage().getCacheSlotSizes() );
            }
            cacheAccess.setForward( processor.getStage().isForward() );

            if ( processor.getStage().isParallel() )
            {
                long highId;
                if ( processor.getStage() == CheckStage.Stage1_NS_PropsLabels )
                {
                    highId = storeAccess.getNodeStore().getHighId();
                }
                else if ( processor.getStage() == CheckStage.Stage8_PS_Props )
                {
                    highId = storeAccess.getPropertyStore().getHighId();
                }
                else
                {
                    highId = storeAccess.getNodeStore().getHighId();
                }
                long recordsPerCPU = RecordDistributor.calculateRecordsPerCpu( highId, numberOfThreads );
                QueueDistributor<R> distributor = distribution.distributor( recordsPerCPU, numberOfThreads );
                processor.applyFilteredParallel( store, progressListener, numberOfThreads, recordsPerCPU, distributor, pageCacheTracer );
            }
            else
            {
                processor.applyFiltered( store, progressListener, pageCacheTracer );
            }
            cacheAccess.setForward( true );
        }
        catch ( Throwable e )
        {
            progressListener.failed( e );
            throw new RuntimeException( e );
        }
        finally
        {
            afterProcessing( processor );
        }
        statistics.print( name );
    }

    protected void beforeProcessing( StoreProcessor processor )
    {
        // intentionally empty
    }

    protected void afterProcessing( StoreProcessor processor )
    {
        // intentionally empty
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[" + name + " @ " + processor.getStage() + ", " +
                store + ":" + store.getHighId() + "]";
    }
}
