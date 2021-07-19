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
package org.neo4j.collection.primitive.hopscotch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongObjectMap;
import org.neo4j.test.randomized.Action;
import org.neo4j.test.randomized.LinePrinter;
import org.neo4j.test.randomized.Printable;
import org.neo4j.test.randomized.RandomizedTester;
import org.neo4j.test.randomized.RandomizedTester.ActionFactory;
import org.neo4j.test.randomized.RandomizedTester.TargetFactory;
import org.neo4j.test.randomized.Result;
import org.neo4j.test.randomized.TestResource;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PrimitiveLongObjectMapRIT
{
    @Test
    void thoroughlyTestIt()
    {
        long endTime = currentTimeMillis() + SECONDS.toMillis( 5 );
        while ( currentTimeMillis() < endTime )
        {
            long seed = currentTimeMillis();
            final Random random = new Random( seed );
            int max = random.nextInt( 10_000 ) + 100;
            RandomizedTester<Maps, String> actions =
                    new RandomizedTester<>( mapFactory(), actionFactory( random ) );

            Result<Maps, String> result = actions.run( max );
            if ( result.isFailure() )
            {
                System.out.println( "Found failure at " + result );
                actions.testCaseWriter( "shouldOnlyContainAddedValues", given() ).print( System.out );
                System.out.println( "Actually, minimal reproducible test of that is..." );
                actions.findMinimalReproducible().testCaseWriter( "shouldOnlyContainAddedValues",
                        given() ).print( System.out );
                fail( "Failed, see printed test case for how to reproduce. Seed:" + seed );
            }

            fullVerification( result.getTarget(), random );
        }
    }

    private static void fullVerification( Maps target, Random random )
    {
        for ( Map.Entry<Long, Integer> entry: target.normalMap.entrySet() )
        {
            assertTrue( target.map.containsKey( entry.getKey() ) );
            assertEquals( entry.getValue(), target.map.get( entry.getKey() ) );
        }

        for ( int i = 0; i < target.normalMap.size(); i++ )
        {
            assertFalse( target.map.containsKey( randomNonExisting( random, target.normalMap ) ) );
        }
    }

    private static Printable given()
    {
        return out -> out.println( PrimitiveLongObjectMap.class.getSimpleName() + "<Integer> map = " +
                Primitive.class.getSimpleName() + ".longObjectMap();" );
    }

    private static ActionFactory<Maps, String> actionFactory( final Random random )
    {
        return from -> generateAction( random, from );
    }

    private static TargetFactory<Maps> mapFactory()
    {
        return Maps::new;
    }

    private static Action<Maps, String> generateAction( Random random, Maps from )
    {
        boolean anExisting = !from.normalMap.isEmpty() && random.nextInt( 3 ) == 0;
        long key = anExisting ?
                   randomExisting( random, from.normalMap ) :
                   randomNonExisting( random, from.normalMap );
        Integer value = random.nextInt( 100 );

        int typeOfAction = random.nextInt( 5 );
        if ( typeOfAction == 0 )
        {   // remove
            return new RemoveAction( key );
        }

        // add
        return new AddAction( key, value );
    }

    private static long randomNonExisting( Random random, Map<Long,Integer> existing )
    {
        while ( true )
        {
            long key = Math.abs( random.nextLong() );
            if ( !existing.containsKey( key ) )
            {
                return key;
            }
        }
    }

    private static long randomExisting( Random random, Map<Long,Integer> existing )
    {
        int index = random.nextInt( existing.size() ) + 1;
        Iterator<Long> iterator = existing.keySet().iterator();
        long value = 0;
        for ( int i = 0; i < index; i++ )
        {
            value = iterator.next();
        }
        return value;
    }

    private static class AddAction implements Action<Maps, String>
    {
        private final long key;
        private final Integer value;

        AddAction( long key, Integer value )
        {
            this.key = key;
            this.value = value;
        }

        @Override
        public String apply( Maps target )
        {
            Integer existingValue = target.normalMap.get( key );
            int actualSizeBefore = target.normalMap.size();

            int sizeBefore = target.map.size();
            boolean existedBefore = target.map.containsKey( key );
            Integer valueBefore = target.map.get( key );
            Integer previous = target.map.put( key, value );
            boolean existsAfter = target.map.containsKey( key );
            Integer valueAfter = target.map.get( key );
            target.normalMap.put( key, value );
            int sizeAfter = target.map.size();

            int actualSizeAfter = target.normalMap.size();
            boolean existing = existingValue != null;
            boolean ok =
                    (sizeBefore == actualSizeBefore) &
                            (existedBefore == existing) &
                            (existing ? existingValue.equals( valueBefore ) : valueBefore == null) &
                            (existing ? previous.equals( existingValue ) : previous == null) &
                            (valueAfter != null && valueAfter.equals( value )) &
                            existsAfter &
                            (sizeAfter == actualSizeAfter);
            return ok ? null : "" + key + ":" + value + "," + existingValue + "," + existedBefore +
                    "," + previous + "," + existsAfter;
        }

        @Override
        public void printAsCode( Maps source, LinePrinter out, boolean includeChecks )
        {
            Integer existingValue = source.normalMap.get( key );

            String addition = "map.put( " + key + ", " + value + " );";
            if ( includeChecks )
            {
                boolean existing = existingValue != null;
                out.println( "int sizeBefore = map.size();" );
                out.println( format( "boolean existedBefore = map.containsKey( %d );", key ) );
                out.println( format( "Integer valueBefore = map.get( %d );", key ) );
                out.println( format( "Integer previous = %s", addition ) );
                out.println( format( "boolean existsAfter = map.containsKey( %d );", key ) );
                out.println( format( "Integer valueAfter = map.get( %d );", key ) );
                out.println( "int sizeAfter = map.size();" );

                int actualSizeBefore = source.normalMap.size();
                out.println( format( "assertEquals( \"%s\", %d, sizeBefore );",
                        "Size before put should have been " + actualSizeBefore, actualSizeBefore ) );
                out.println( format( "assert%s( \"%s\", existedBefore );", capitilize( existing ),
                        key + " should " + (existing ? "" : "not ") + "exist before putting here" ) );
                if ( existing )
                {
                    out.println( format( "assertEquals( \"%s\", (Integer)%d, valueBefore );",
                            "value before should be " + existingValue, existingValue ) );
                    out.println( format( "assertEquals( \"%s\", (Integer)%d, previous );",
                            "value returned from put should be " + existingValue, existingValue ) );
                }
                else
                {
                    out.println( format( "assertNull( \"%s\", valueBefore );",
                            "value before putting should be null" ) );
                    out.println( format( "assertNull( \"%s\", previous );",
                            "value returned from putting should be null" ) );
                }
                out.println( format( "assertTrue( \"%s\", existsAfter );",
                        key + " should exist" ) );
                out.println( format( "assertEquals( \"%s\", (Integer)%d, valueAfter );",
                        "value after putting should be " + value, value ) );
                int actualSizeAfter = existing ? actualSizeBefore : actualSizeBefore + 1;
                out.println( format( "assertEquals( \"%s\", %d, sizeAfter );",
                        "Size after put should have been " + actualSizeAfter, actualSizeAfter ) );
            }
            else
            {
                out.println( addition );
            }
        }
    }

    private static class RemoveAction implements Action<Maps, String>
    {
        private final long key;

        RemoveAction( long key )
        {
            this.key = key;
        }

        @Override
        public String apply( Maps target )
        {
            Integer existingValue = target.normalMap.get( key );

            boolean existedBefore = target.map.containsKey( key );
            Integer valueBefore = target.map.get( key );
            Integer removed = target.map.remove( key );
            boolean existsAfter = target.map.containsKey( key );
            Integer valueAfter = target.map.get( key );
            target.normalMap.remove( key );

            boolean existing = existingValue != null;
            boolean ok =
                    (existedBefore == existing) &
                            (existing ? valueBefore.equals( existingValue ) : valueBefore == null) &
                            (existing ? removed.equals( existingValue ) : removed == null) &
                            (valueAfter == null) & !existsAfter;
            return ok ? null : "" + key + "," + existingValue + "," + existedBefore +
                    "," + removed + "," + existsAfter;
        }

        @Override
        public void printAsCode( Maps source, LinePrinter out, boolean includeChecks )
        {
            Integer existingValue = source.normalMap.get( key );

            String removal = "map.remove( " + key + " );";
            if ( includeChecks )
            {
                boolean existing = existingValue != null;
                out.println( format( "boolean existedBefore = map.containsKey( %d );", key ) );
                out.println( format( "Integer valueBefore = map.get( %d );", key ) );
                out.println( format( "Integer removed = %s", removal ) );
                out.println( format( "boolean existsAfter = map.containsKey( %d );", key ) );
                out.println( format( "Integer valueAfter = map.get( %d );", key ) );

                out.println( format( "assert%s( \"%s\", existedBefore );", capitilize( existing ),
                        key + " should " + (existing ? "" : "not ") + "exist before putting here" ) );
                if ( existing )
                {
                    out.println( format( "assertEquals( \"%s\", (Integer)%d, valueBefore );",
                            "value before should be " + existingValue, existingValue ) );
                    out.println( format( "assertEquals( \"%s\", (Integer)%d, removed );",
                            "value returned from put should be " + existingValue, existingValue ) );
                }
                else
                {
                    out.println( format( "assertNull( \"%s\", valueBefore );",
                            "value before putting should be null" ) );
                    out.println( format( "assertNull( \"%s\", removed );",
                            "value returned from putting should be null" ) );
                }
                out.println( format( "assertFalse( \"%s\", existsAfter );",
                        key + " should not exist" ) );
                out.println( format( "assertNull( \"%s\", valueAfter );",
                        "value after removing should be null" ) );
            }
            else
            {
                out.println( removal );
            }
        }
    }

    private static String capitilize( boolean bool )
    {
        String string = Boolean.valueOf( bool ).toString();
        return string.substring( 0, 1 ).toUpperCase() + string.substring( 1 ).toLowerCase();
    }

    private static class Maps implements TestResource
    {
        final Map<Long, Integer> normalMap = new HashMap<>();
        final PrimitiveLongObjectMap<Integer> map = Primitive.longObjectMap();

        @Override
        public String toString()
        {
            return map.toString();
        }

        @Override
        public void close()
        {
        }
    }
}
