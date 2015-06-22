/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.tools;

import com.google.common.collect.BiMap;
import com.google.common.io.Files;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.usergrid.ServiceITSetup;
import org.apache.usergrid.ServiceITSetupImpl;
import org.apache.usergrid.ServiceITSuite;
import org.apache.usergrid.management.ApplicationInfo;
import org.apache.usergrid.management.OrganizationInfo;
import org.apache.usergrid.management.OrganizationOwnerInfo;
import org.apache.usergrid.management.UserInfo;
import org.apache.usergrid.persistence.Entity;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.utils.UUIDUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.ClassRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.util.*;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class MigrationTest {
    static final Logger logger = LoggerFactory.getLogger( MigrationTest.class );

    @ClassRule
    public static ServiceITSetup setup = new ServiceITSetupImpl( ServiceITSuite.cassandraResource );

    @org.junit.Test
    public void testExportUserAndOrg() throws Exception {


        // Create orgOwner+Application
        int numOfEntities = 10;

        final String random1 = RandomStringUtils.randomAlphanumeric( 10 );

        String applicationName = "app"+random1;


        OrganizationOwnerInfo orgMade = null;
        ApplicationInfo appMade = null;
        //for ( int i = 0; i < 1; i++ ) {
        orgMade = setup.getMgmtSvc().createOwnerAndOrganization(
                "org_" + random1, "user_" + random1, "user_" + random1,
                "user_" + random1 + "@example.com", "password" );
        appMade = setup.getMgmtSvc().createApplication( orgMade.getOrganization().getUuid(), applicationName );

        //for ( int j = 0; j < 5; j++ ) {

        EntityManager customMaker = setup.getEmf().getEntityManager( appMade.getId() );
        customMaker.createApplicationCollection( "superappCol" + random1 );
        //intialize user object to be posted
        Map<String, Object> entityLevelProperties = null;
        Entity[] entNotCopied;
        entNotCopied = new Entity[numOfEntities];
        //creates 10 entities
        for ( int index = 0; index < numOfEntities; index++ ) {
            entityLevelProperties = new LinkedHashMap<String, Object>();
            entityLevelProperties.put( "username", "user_"+index );
            entNotCopied[index] = customMaker.create( "superappCol" + random1, entityLevelProperties );
        }
        //    }
       // }


        // export to file

        String directoryName = "./target/export" + RandomStringUtils.randomAlphanumeric(10);

        Migration migrationInstance = new Migration();
        migrationInstance.startTool( new String[] {
                "-host", "localhost:" + ServiceITSuite.cassandraResource.getRpcPort(),
                "-orgId",orgMade.getOrganization().getUuid().toString(),"-appName",applicationName ,
                "-outputDir", directoryName
        }, false );

        // read, parse and verify files

        // first, the admin users file

        File directory = new File( directoryName );
        String[] adminUsersFileNames = directory.list( new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("admin-users.");
            }
        });

        // only one. read it into a map

        File adminUsersFile = new File(
                directory.getAbsolutePath() + File.separator + adminUsersFileNames[0] );

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree( adminUsersFile );
        assertTrue( node.isArray() );

        // does file contain our two admin users?

        Set<String> usernames = new HashSet<String>();
        for ( int i=0; i<node.size(); i++) {
            JsonNode jsonNode = node.get( i );
            if ( jsonNode.get( "entity" ).get( "username" )!= null )
                usernames.add( jsonNode.get( "entity" ).get( "username" ).asText());
        }

        //TODO: crappy way of doing verificatino that all users were written to the file.
        for ( int i = 0; i < 10; i++ ) {
            assertTrue( usernames.contains( "user_"+i ) );
        }

        // only one, read it into a map


        //TODO:GREY do some verification of the metadata that is now included with the entity.
//
//        mapper = new ObjectMapper();
//        node = mapper.readTree( metadataFile );
//        assertTrue( node.isObject() );

        // do users belong to correct orgs

//        JsonNode user1node = node.findValue( orgOwnerInfo1.getOwner().getUuid().toString() );
//        JsonNode orgs1 = user1node.findValue( "organizations");
//        assertEquals( 2, orgs1.size() );
//
//        JsonNode user2node = node.findValue( orgOwnerInfo2.getOwner().getUuid().toString() );
//        JsonNode orgs2 = user2node.findValue( "organizations");
//        assertEquals( 1, orgs2.size() );
    }

}