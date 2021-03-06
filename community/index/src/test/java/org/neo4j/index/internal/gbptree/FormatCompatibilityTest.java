/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.index.internal.gbptree;

import org.apache.commons.lang3.mutable.MutableLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.neo4j.cursor.RawCursor;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static java.lang.String.format;
import static java.util.Arrays.asList;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.neo4j.index.internal.gbptree.SimpleLongLayout.longLayout;
import static org.neo4j.test.rule.PageCacheRule.config;

/**
 * A little trick to automatically tell whether or not index format was changed without
 * incrementing the format version. This is done by keeping a zipped tree which is opened and tested on.
 * On failure this test will fail saying that the format version needs update and also update the zipped
 * store with the new version.
 */
@RunWith( Parameterized.class )
public class FormatCompatibilityTest
{
    private static final String STORE = "store";
    private static final String TREE_CLASS_NAME = GBPTree.class.getSimpleName();
    private static final int KEY_COUNT = 10_000;
    private static final String CURRENT_FIXED_SIZE_FORMAT_ZIP = "current-format.zip";
    private static final String CURRENT_DYNAMIC_SIZE_FORMAT_ZIP = "current-dynamic-format.zip";

    @Parameters
    public static List<Object[]> data()
    {
        return asList(
                new Object[] {longLayout().withFixedSize( true ).build(), CURRENT_FIXED_SIZE_FORMAT_ZIP},
                new Object[] {longLayout().withFixedSize( false ).build(), CURRENT_DYNAMIC_SIZE_FORMAT_ZIP} );
    }

    @Parameter
    public SimpleLongLayout layout;
    @Parameter( 1 )
    public String zipName;

    private final TestDirectory directory = TestDirectory.testDirectory( getClass() );
    private final PageCacheRule pageCacheRule = new PageCacheRule( config().withInconsistentReads( false ) );
    private final DefaultFileSystemRule fsRule = new DefaultFileSystemRule();

    @Rule
    public final RuleChain chain = RuleChain.outerRule( fsRule ).around( directory ).around( pageCacheRule );

    @Test
    public void shouldDetectFormatChange() throws Throwable
    {
        // GIVEN stored tree
        File storeFile = directory.file( STORE );
        try
        {
            unzipTo( storeFile );
        }
        catch ( FileNotFoundException e )
        {
            // First time this test is run, eh?
            createAndZipTree( storeFile );
            tellDeveloperToCommitThisFormatVersion();
        }
        assertTrue( zipName + " seems to be missing from resources directory", fsRule.get().fileExists( storeFile ) );

        // WHEN reading from the tree
        // THEN everything should work, otherwise there has likely been a format change
        PageCache pageCache = pageCacheRule.getPageCache( fsRule.get() );
        try ( GBPTree<MutableLong,MutableLong> tree =
                new GBPTreeBuilder<>( pageCache, storeFile, layout ).build() )
        {
            try
            {
                tree.consistencyCheck();
                try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> cursor =
                        tree.seek( new MutableLong( 0 ), new MutableLong( KEY_COUNT ) ) )
                {
                    for ( long expectedKey = 0; cursor.next(); expectedKey++ )
                    {
                        Hit<MutableLong,MutableLong> hit = cursor.get();
                        assertEquals( expectedKey, hit.key().longValue() );
                        assertEquals( value( expectedKey ), hit.value().longValue() );
                    }
                    assertFalse( cursor.next() );
                }
            }
            catch ( Throwable t )
            {
                throw new AssertionError( format(
                        "If this is the single failing test for %s this failure is a strong indication that format " +
                                "has changed without also incrementing %s.FORMAT_VERSION. " +
                                "Please go ahead and increment the format version",
                        TREE_CLASS_NAME, TREE_CLASS_NAME ), t );
            }
        }
        catch ( MetadataMismatchException e )
        {
            // Good actually, or?
            assertThat( e.getMessage(), containsString( "format version" ) );

            fsRule.get().deleteFile( storeFile );
            createAndZipTree( storeFile );

            tellDeveloperToCommitThisFormatVersion();
        }
    }

    private void tellDeveloperToCommitThisFormatVersion()
    {
        fail( format( "This is merely a notification to developer. Format has changed and its version has also " +
                "been properly incremented. A tree with this new format has been generated and should be committed. " +
                "Please move:%n  %s%ninto %n  %s, %nreplacing the existing file there",
                directory.file( zipName ),
                "<index-module>" + pathify( ".src.test.resources." ) +
                pathify( getClass().getPackage().getName() + "." ) + zipName ) );
    }

    private static String pathify( String name )
    {
        return name.replace( '.', File.separatorChar );
    }

    private void unzipTo( File storeFile ) throws IOException
    {
        URL resource = getClass().getResource( zipName );
        if ( resource == null )
        {
            throw new FileNotFoundException();
        }

        try ( ZipFile zipFile = new ZipFile( resource.getFile() ) )
        {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            assertTrue( entries.hasMoreElements() );
            ZipEntry entry = entries.nextElement();
            assertEquals( STORE, entry.getName() );
            Files.copy( zipFile.getInputStream( entry ), storeFile.toPath() );
        }
    }

    private void createAndZipTree( File storeFile ) throws IOException
    {
        PageCache pageCache = pageCacheRule.getPageCache( fsRule.get() );
        try ( GBPTree<MutableLong,MutableLong> tree =
                new GBPTreeBuilder<>( pageCache, storeFile, layout ).build() )
        {
            MutableLong insertKey = new MutableLong();
            MutableLong insertValue = new MutableLong();
            int batchSize = KEY_COUNT / 10;
            for ( int i = 0, key = 0; i < 10; i++ )
            {
                try ( Writer<MutableLong,MutableLong> writer = tree.writer() )
                {
                    for ( int j = 0; j < batchSize; j++, key++ )
                    {
                        insertKey.setValue( key );
                        insertValue.setValue( value( key ) );
                        writer.put( insertKey, insertValue );
                    }
                }
                tree.checkpoint( IOLimiter.unlimited() );
            }
        }
        zip( storeFile );
    }

    private static long value( long key )
    {
        return key * 2;
    }

    private File zip( File toZip ) throws IOException
    {
        File targetFile = directory.file( zipName );
        try ( ZipOutputStream out = new ZipOutputStream( new FileOutputStream( targetFile ) ) )
        {
            ZipEntry entry = new ZipEntry( toZip.getName() );
            entry.setSize( toZip.length() );
            out.putNextEntry( entry );
            Files.copy( toZip.toPath(), out );
        }
        return targetFile;
    }
}
