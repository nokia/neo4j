/*
 * Copyright (c) "Neo4j"
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
package org.neo4j.io.pagecache.impl.muninn;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.graphdb.config.Configuration;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.DelegatingPageSwapper;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageEvictionCallback;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.PageSwapperFactory;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.impl.SingleFilePageSwapperFactory;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracerSupplier;
import org.neo4j.io.pagecache.tracing.cursor.context.EmptyVersionContextSupplier;
import org.neo4j.kernel.impl.scheduler.JobSchedulerFactory;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.test.rule.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MuninnPageCursorTest
{
    @Rule
    public final TestDirectory directory = TestDirectory.testDirectory();

    private final LifeSupport life = new LifeSupport();
    private JobScheduler jobScheduler;

    @Before
    public void start()
    {
        jobScheduler = JobSchedulerFactory.createScheduler();
        life.add( jobScheduler );
        life.start();
    }

    @After
    public void stop()
    {
        life.shutdown();
    }

    @Test
    public void shouldUnlockLatchOnPageFaultingWhenConcurrentlyCursorClosedThrowOnPageFaultRead() throws IOException
    {
        shouldUnlockLatchOnPageFaultingWhenConcurrentlyCursorClosed( true );
    }

    @Test
    public void shouldUnlockLatchOnPageFaultingWhenConcurrentlyCursorClosedDontThrowOnPageFaultRead() throws IOException
    {
        shouldUnlockLatchOnPageFaultingWhenConcurrentlyCursorClosed( false );
    }

    private void shouldUnlockLatchOnPageFaultingWhenConcurrentlyCursorClosed( boolean alsoThrowOnPageFaultRead ) throws IOException
    {
        // given
        File file = directory.file( "dude" );
        createSomeData( file );

        // when
        AtomicReference<PageCursor> cursorHolder = new AtomicReference<>();
        Runnable onReadAction = () ->
        {
            PageCursor cursor = cursorHolder.get();
            if ( cursor != null )
            {
                cursor.close();
                if ( alsoThrowOnPageFaultRead )
                {
                    // Alternatively also throw here to cause the problem happening in a slightly different place.
                    throw new RuntimeException();
                }
            }
        };
        try ( PageCache pageCache = startPageCache( customSwapper( defaultPageSwapperFactory(), onReadAction ) );
                PagedFile pagedFile = pageCache.map( file, PageCache.PAGE_SIZE, StandardOpenOption.CREATE ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PagedFile.PF_SHARED_WRITE_LOCK ) )
            {
                cursorHolder.set( cursor ); // enabling the failing behaviour
                assertThrows( NullPointerException.class, cursor::next );
                cursorHolder.set( null ); // disabling this failing behaviour
            }

            // then hopefully the latch is not jammed. Assert that we can read normally from this new cursor.
            try ( PageCursor cursor = pagedFile.io( 0, PagedFile.PF_SHARED_WRITE_LOCK ) )
            {
                for ( int i = 0; i < 100; i++ )
                {
                    cursor.next( i );
                    for ( int j = 0; j < 100; j++ )
                    {
                        assertEquals( j, cursor.getLong() );
                    }
                }
            }
        }
    }

    private PageCache startPageCache( PageSwapperFactory pageSwapperFactory )
    {
        return new MuninnPageCache( pageSwapperFactory, 1_000, PageCacheTracer.NULL, PageCursorTracerSupplier.NULL, EmptyVersionContextSupplier.EMPTY,
                jobScheduler );
    }

    private void createSomeData( File file ) throws IOException
    {
        try ( PageCache pageCache = startPageCache( defaultPageSwapperFactory() );
                PagedFile pagedFile = pageCache.map( file, PageCache.PAGE_SIZE, StandardOpenOption.CREATE );
                PageCursor cursor = pagedFile.io( 0, PagedFile.PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < 100; i++ )
            {
                cursor.next( i );
                for ( int j = 0; j < 100; j++ )
                {
                    cursor.putLong( j );
                }
            }
        }
    }

    private PageSwapperFactory customSwapper( PageSwapperFactory actual, Runnable onRead )
    {
        return new PageSwapperFactory()
        {
            @Override
            public void open( FileSystemAbstraction fs, Configuration config )
            {
                actual.open( fs, config );
            }

            @Override
            public String implementationName()
            {
                return actual.implementationName();
            }

            @Override
            public long getRequiredBufferAlignment()
            {
                return actual.getRequiredBufferAlignment();
            }

            @Override
            public PageSwapper createPageSwapper( File file, int filePageSize, PageEvictionCallback onEviction, boolean createIfNotExist,
                    boolean noChannelStriping ) throws IOException
            {
                PageSwapper actualSwapper = actual.createPageSwapper( file, filePageSize, onEviction, createIfNotExist, noChannelStriping );
                return new DelegatingPageSwapper( actualSwapper )
                {
                    @Override
                    public long read( long filePageId, long bufferAddress, int bufferSize ) throws IOException
                    {
                        onRead.run();
                        return super.read( filePageId, bufferAddress, bufferSize );
                    }
                };
            }

            @Override
            public void syncDevice()
            {
                actual.syncDevice();
            }

            @Override
            public void close()
            {
                actual.close();
            }
        };
    }

    private PageSwapperFactory defaultPageSwapperFactory()
    {
        SingleFilePageSwapperFactory swapperFactory = new SingleFilePageSwapperFactory();
        swapperFactory.open( directory.getFileSystem(), null );
        return swapperFactory;
    }
}
