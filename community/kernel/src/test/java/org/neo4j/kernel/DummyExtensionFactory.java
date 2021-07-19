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
package org.neo4j.kernel;

import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.spi.KernelContext;
import org.neo4j.kernel.internal.KernelData;
import org.neo4j.kernel.lifecycle.Lifecycle;

public class DummyExtensionFactory extends KernelExtensionFactory<DummyExtensionFactory.Dependencies>
{
    public interface Dependencies
    {
        Config getConfig();

        KernelData getKernel();

        DatabaseManager getDatabaseManager();
    }

    static final String EXTENSION_ID = "dummy";

    public DummyExtensionFactory()
    {
        super( EXTENSION_ID );
    }

    @Override
    public Lifecycle newInstance( KernelContext context, Dependencies dependencies )
    {
        return new DummyExtension( dependencies );
    }
}
